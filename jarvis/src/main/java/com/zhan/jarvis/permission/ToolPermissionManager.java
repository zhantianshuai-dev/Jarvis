package com.zhan.jarvis.permission;

import com.zhan.jarvis.hook.impl.GitPolicyHook;
import com.zhan.jarvis.tool.ToolContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 所有工具调用前的权限网关。
 * 第一版先把 Git 远程/破坏性操作升级为通用 pending permission。
 */
@Component
public class ToolPermissionManager {

    public static final String CONFIRM_ENDPOINT = "/api/v1/tools/confirm";
    public static final String META_CONFIRM_ID = "confirm_id";
    private static final Duration CONFIRM_TTL = Duration.ofMinutes(10);
    private static final Set<String> GIT_CONFIRM_ACTIONS = Set.of("push", "pull", "restore");

    private final PendingPermissionStore pendingStore;

    public ToolPermissionManager(PendingPermissionStore pendingStore) {
        this.pendingStore = pendingStore;
    }

    public ToolPermissionDecision evaluate(String toolName, Map<String, Object> arguments, ToolContext ctx) {
        if (isHumanConfirmed(ctx)) {
            return ToolPermissionDecision.allow();
        }
        //如果是git命令，就开始校验
        if ("git".equals(toolName)) {
            return evaluateGit(arguments, ctx);
        }
        return ToolPermissionDecision.allow();
    }

    public PendingToolPermission take(String confirmId) {
        return pendingStore.take(confirmId)
                .orElseThrow(() -> new IllegalArgumentException("确认操作不存在或已过期: " + confirmId));
    }

    private ToolPermissionDecision evaluateGit(Map<String, Object> arguments, ToolContext ctx) {
        String action = stringArg(arguments, "action", "");
        if (!GIT_CONFIRM_ACTIONS.contains(action)) {
            return ToolPermissionDecision.allow();
        }

        var command = gitCommand(action, arguments);
        String summary = "执行 " + String.join(" ", command);
        String message = switch (action) {
            case "push" -> "即将执行 git push，把本地分支推送到远程仓库。确认后会影响远程仓库。";
            case "pull" -> "即将执行 git pull --ff-only，从远程拉取并更新本地分支。确认前请确保工作区状态可接受。";
            case "restore" -> "即将执行 git restore，指定路径上的未提交修改会被丢弃。";
            default -> "该工具操作需要人工确认。";
        };

        String confirmId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(CONFIRM_TTL);
        var safeArguments = new LinkedHashMap<String, Object>(arguments == null ? Map.of() : arguments);
        var safeMetadata = new LinkedHashMap<String, Object>(ctx.metadata() == null ? Map.of() : ctx.metadata());
        pendingStore.put(new PendingToolPermission(
                confirmId,
                "git",
                safeArguments,
                ctx.sessionId(),
                ctx.sessionKey(),
                ctx.workspaceDir(),
                ctx.userId(),
                safeMetadata,
                summary,
                expiresAt
        ));

        var payload = new LinkedHashMap<String, Object>();
        payload.put("requires_confirmation", true);
        payload.put("confirm_id", confirmId);
        payload.put("tool", "git");
        payload.put("action", action);
        payload.put("summary", summary);
        payload.put("command", command);
        payload.put("message", message);
        payload.put("expires_at", expiresAt.toString());
        payload.put("confirm_endpoint", CONFIRM_ENDPOINT);
        //这里返回ask给agentLoop，说明这是需要询问用户的
        return ToolPermissionDecision.ask(payload);
    }

    //这里校验human_confirmed=true，如果不为true，就返回false
    private static boolean isHumanConfirmed(ToolContext ctx) {
        Map<String, Object> metadata = ctx.metadata();
        if (metadata == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(metadata.getOrDefault(GitPolicyHook.META_HUMAN_CONFIRMED, false)));
    }

    private static List<String> gitCommand(String action, Map<String, Object> arguments) {
        return switch (action) {
            case "push" -> List.of("git", "push", remoteArg(arguments), branchArg(arguments, "push 需要显式 branch 参数"));
            case "pull" -> List.of("git", "pull", "--ff-only", remoteArg(arguments), branchArg(arguments, "pull 需要显式 branch 参数"));
            case "restore" -> {
                List<String> paths = pathArgs(arguments);
                if (paths.isEmpty()) {
                    throw new IllegalArgumentException("restore 需要显式 paths，禁止默认恢复全部文件");
                }
                var command = new ArrayList<String>();
                command.add("git");
                command.add("restore");
                command.add("--");
                command.addAll(paths);
                yield List.copyOf(command);
            }
            default -> throw new IllegalArgumentException("不支持的确认 action: " + action);
        };
    }

    private static String remoteArg(Map<String, Object> arguments) {
        String remote = stringArg(arguments, "remote", "origin");
        if (remote.isBlank()) {
            remote = "origin";
        }
        rejectUnsafeArg(remote, "remote");
        if (remote.contains("/") || remote.contains("\\") || remote.contains("..") || remote.contains(" ")) {
            throw new IllegalArgumentException("remote 名称不安全");
        }
        return remote;
    }

    private static String branchArg(Map<String, Object> arguments, String missingMessage) {
        String branch = stringArg(arguments, "branch", "");
        if (branch.isBlank()) {
            throw new IllegalArgumentException(missingMessage);
        }
        rejectUnsafeBranch(branch);
        return branch;
    }

    private static List<String> pathArgs(Map<String, Object> arguments) {
        Object raw = arguments != null ? arguments.get("paths") : null;
        var values = new ArrayList<String>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    values.add(item.toString());
                }
            }
        } else if (raw instanceof String text && !text.isBlank()) {
            values.add(text);
        }
        var result = new ArrayList<String>();
        for (String value : values) {
            String path = value == null ? "" : value.strip();
            if (path.isBlank() || ".".equals(path) || "./".equals(path)) {
                throw new IllegalArgumentException("paths 必须显式到文件或子目录，禁止传 .");
            }
            rejectUnsafeArg(path, "paths");
            result.add(path);
        }
        return List.copyOf(result);
    }

    private static void rejectUnsafeBranch(String branch) {
        rejectUnsafeArg(branch, "branch");
        if (branch.contains(" ") || branch.contains("\\") || branch.contains("..")
                || branch.contains("@{") || branch.contains("//")
                || branch.startsWith("/") || branch.endsWith("/")) {
            throw new IllegalArgumentException("branch 名称不安全或不符合 Git 分支命名习惯");
        }
    }

    private static void rejectUnsafeArg(String value, String name) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (value.startsWith("-")) {
            throw new IllegalArgumentException(name + " 不能以 - 开头");
        }
        if (value.contains("\u0000") || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException(name + " 包含非法控制字符");
        }
    }

    private static String stringArg(Map<String, Object> args, String key, String fallback) {
        if (args == null) {
            return fallback;
        }
        Object value = args.get(key);
        return value == null ? fallback : value.toString().strip();
    }
}
