package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.tool.McpTool;
import com.zhan.jarvis.tool.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Git 工具。
 * 当前开放只读查询、本地 add/commit 和新建分支。
 * push/pull/restore 使用二次确认；reset/clean/force push 仍禁止。
 */
public class GitTool implements McpTool {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_CHARS = 50_000;
    private static final int DEFAULT_LOG_LIMIT = 10;
    private static final int MAX_LOG_LIMIT = 50;
    private static final int MAX_DIFF_OUTPUT_CHARS = 12_000;

    private final ObjectMapper objectMapper;
    private final Path defaultGitWorkspace;

    public GitTool(ObjectMapper objectMapper, String gitWorkspaceDir) {
        this.objectMapper = objectMapper;
        this.defaultGitWorkspace = Path.of(gitWorkspaceDir).toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "git";
    }

    @Override
    public String description() {
        return "Git 工具。支持查看状态、diff、提交历史、分支、提交详情、显式路径 add、commit 和新建分支；push/pull/restore 需要二次确认；不支持 reset、clean、force push。";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = objectMapper.createObjectNode().put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("action")
                .put("type", "string")
                .put("description", "Git 操作类型。")
                .putArray("enum")
                .add("status")
                .add("diff")
                .add("log")
                .add("branch")
                .add("show")
                .add("root")
                .add("add")
                .add("commit")
                .add("checkout_new_branch")
                .add("fetch")
                .add("push")
                .add("pull")
                .add("restore");
        props.putObject("path")
                .put("type", "string")
                .put("description", "可选，仓库内路径。diff 时表示要查看的文件/目录；其他 action 表示执行目录。");
        props.putObject("target")
                .put("type", "string")
                .put("description", "可选，show 的提交 SHA/引用名，或 diff 的提交/引用范围。不要传 --cached，staged diff 请用 scope=staged。");
        props.putObject("scope")
                .put("type", "string")
                .put("description", "diff 范围。unstaged=工作区未暂存，staged=已暂存，all=同时查看未暂存和已暂存。默认 unstaged。")
                .putArray("enum")
                .add("unstaged")
                .add("staged")
                .add("all");
        props.putObject("limit")
                .put("type", "integer")
                .put("minimum", 1)
                .put("maximum", MAX_LOG_LIMIT)
                .put("description", "log 返回条数，默认 10，最大 50。");
        props.putObject("stat")
                .put("type", "boolean")
                .put("description", "diff 是否只返回统计摘要，默认 true。只有用户明确要求具体补丁时才设为 false。");
        props.putObject("paths")
                .put("type", "array")
                .put("description", "add/commit 要处理的显式仓库内路径列表。不能传 . 或空列表。")
                .putObject("items")
                .put("type", "string");
        props.putObject("message")
                .put("type", "string")
                .put("description", "commit message，commit action 必填。");
        props.putObject("branch")
                .put("type", "string")
                .put("description", "checkout_new_branch 要创建的新分支名；push/pull 的目标分支。");
        props.putObject("remote")
                .put("type", "string")
                .put("description", "fetch/push/pull 的远程名，默认 origin。");
        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        String action = stringArg(arguments, "action", "");
        if (action.isBlank()) {
            return toJson(Map.of("error", "缺少 action 参数"));
        }

        Path gitWorkspace = workspaceRoot(ctx);
        Path cwd;
        try {
            cwd = "diff".equals(action)
                    ? gitWorkspace
                    : resolvePath(gitWorkspace, stringArg(arguments, "path", "."));
        } catch (IOException e) {
            return toJson(Map.of("error", e.getMessage()));
        }

        List<String> command;
        try {
            //这里直接判断是否是写入到动作add commit branch
            if (isWriteAction(action)) {
                return executeWriteAction(action, arguments, gitWorkspace, cwd);
            }
            command = buildCommand(action, arguments, gitWorkspace);
        } catch (IllegalArgumentException e) {
            return toJson(Map.of("error", e.getMessage(), "action", action));
        }

        var result = runGit(command, cwd);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("action", action);
        payload.put("cwd", cwd.toString());
        payload.put("command", command);
        payload.put("exit_code", result.exitCode());
        payload.put("timed_out", result.timedOut());
        payload.put("output", maybeTruncateDiffOutput(action, result.output(), payload));
        if (result.exitCode() != 0 || result.timedOut()) {
            payload.put("error", "Git 命令执行失败");
        }
        return toJson(payload);
    }

    private List<String> buildCommand(String action, Map<String, Object> arguments, Path gitWorkspace) {
        return switch (action) {
            case "root" -> List.of("git", "rev-parse", "--show-toplevel");
            case "status" -> List.of("git", "status", "--short", "--branch");
            case "branch" -> List.of("git", "branch", "--all", "--verbose", "--no-abbrev");
            case "log" -> {
                int limit = Math.max(1, Math.min(MAX_LOG_LIMIT, intArg(arguments, "limit", DEFAULT_LOG_LIMIT)));
                yield List.of("git", "log", "--oneline", "--decorate", "--max-count=" + limit);
            }
            case "diff" -> buildDiffCommand(arguments, gitWorkspace);
            case "show" -> buildShowCommand(arguments);
            case "fetch" -> buildFetchCommand(arguments);
            default -> throw new IllegalArgumentException("不支持的 Git action: " + action);
        };
    }

    private String executeWriteAction(String action, Map<String, Object> arguments, Path gitWorkspace, Path cwd) {
        return switch (action) {
            case "add" -> executeAdd(arguments, gitWorkspace, cwd);
            case "commit" -> executeCommit(arguments, gitWorkspace, cwd);
            case "checkout_new_branch" -> executeCheckoutNewBranch(arguments, cwd);
            case "push" -> executePush(arguments, cwd);
            case "pull" -> executePull(arguments, cwd);
            case "restore" -> executeRestore(arguments, gitWorkspace, cwd);
            default -> toJson(Map.of("error", "不支持的本地写操作: " + action));
        };
    }

    private String executePush(Map<String, Object> arguments, Path cwd) {
        String remote = remoteArg(arguments);
        String branch = branchArg(arguments, "push 需要显式 branch 参数");
        var command = List.of("git", "push", remote, branch);
        return writeResult("push", cwd, List.of(runGit(command, cwd)), List.of(command));
    }

    private String executePull(Map<String, Object> arguments, Path cwd) {
        String remote = remoteArg(arguments);
        String branch = branchArg(arguments, "pull 需要显式 branch 参数");
        var command = List.of("git", "pull", "--ff-only", remote, branch);
        return writeResult("pull", cwd, List.of(runGit(command, cwd)), List.of(command));
    }

    private String executeRestore(Map<String, Object> arguments, Path gitWorkspace, Path cwd) {
        List<String> paths = pathArgs(arguments, gitWorkspace);
        if (paths.isEmpty()) {
            return toJson(Map.of("error", "restore 需要显式 paths，禁止默认恢复全部文件"));
        }
        var command = new ArrayList<String>();
        command.add("git");
        command.add("restore");
        command.add("--");
        command.addAll(paths);
        return writeResult("restore", cwd, List.of(runGit(command, cwd)), List.of(command));
    }

    private String executeAdd(Map<String, Object> arguments, Path gitWorkspace, Path cwd) {
        List<String> paths = pathArgs(arguments, gitWorkspace);
        if (paths.isEmpty()) {
            return toJson(Map.of("error", "add 需要显式 paths，禁止默认添加全部文件"));
        }
        var command = new ArrayList<String>();
        command.add("git");
        command.add("add");
        command.add("--");
        command.addAll(paths);
        return writeResult("add", cwd, List.of(runGit(command, cwd)), List.of(command));
    }

    private String executeCommit(Map<String, Object> arguments, Path gitWorkspace, Path cwd) {
        String message = stringArg(arguments, "message", "");
        if (message.isBlank()) {
            return toJson(Map.of("error", "commit 需要非空 message"));
        }
        rejectUnsafeCommitMessage(message);

        List<String> paths = pathArgs(arguments, gitWorkspace);
        if (paths.isEmpty()) {
            return toJson(Map.of("error", "commit 需要显式 paths，禁止提交未明确指定的文件"));
        }

        var commands = new ArrayList<List<String>>();
        var results = new ArrayList<GitResult>();

        var statusBefore = List.of("git", "status", "--short");
        commands.add(statusBefore);
        results.add(runGit(statusBefore, cwd));

        var diffStatBefore = commandWithPaths(List.of("git", "diff", "--stat", "--"), paths);
        commands.add(diffStatBefore);
        results.add(runGit(diffStatBefore, cwd));

        var add = new ArrayList<String>();
        add.add("git");
        add.add("add");
        add.add("--");
        add.addAll(paths);
        commands.add(add);
        GitResult addResult = runGit(add, cwd);
        results.add(addResult);
        if (addResult.exitCode() != 0 || addResult.timedOut()) {
            return writeResult("commit", cwd, results, commands);
        }

        var stagedCheck = List.of("git", "diff", "--cached", "--quiet");
        GitResult stagedCheckResult = runGit(stagedCheck, cwd);
        commands.add(stagedCheck);
        results.add(stagedCheckResult);
        //先判断一下是否有修改
        if (stagedCheckResult.exitCode() == 0) {
            var payload = baseWritePayload("commit", cwd, results, commands);
            payload.put("error", "没有 staged changes，拒绝创建空提交");
            return toJson(payload);
        }
        //正式定义提交command
        var commit = List.of("git", "commit", "-m", message);
        commands.add(commit);
        results.add(runGit(commit, cwd));

        var statusAfter = List.of("git", "status", "--short", "--branch");
        commands.add(statusAfter);
        results.add(runGit(statusAfter, cwd));
        return writeResult("commit", cwd, results, commands);
    }

    private String executeCheckoutNewBranch(Map<String, Object> arguments, Path cwd) {
        String branch = stringArg(arguments, "branch", "");
        if (branch.isBlank()) {
            return toJson(Map.of("error", "checkout_new_branch 需要 branch 参数"));
        }
        rejectUnsafeBranch(branch);

        var commands = new ArrayList<List<String>>();
        var results = new ArrayList<GitResult>();

        var check = List.of("git", "check-ref-format", "--branch", branch);
        commands.add(check);
        GitResult checkResult = runGit(check, cwd);
        results.add(checkResult);
        if (checkResult.exitCode() != 0 || checkResult.timedOut()) {
            return writeResult("checkout_new_branch", cwd, results, commands);
        }

        var checkout = List.of("git", "checkout", "-b", branch);
        commands.add(checkout);
        results.add(runGit(checkout, cwd));
        return writeResult("checkout_new_branch", cwd, results, commands);
    }

    private List<String> buildDiffCommand(Map<String, Object> arguments, Path gitWorkspace) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("diff");
        String scope = stringArg(arguments, "scope", "unstaged");
        if (scope.isBlank()) {
            scope = "unstaged";
        }
        if (!List.of("unstaged", "staged", "all").contains(scope)) {
            throw new IllegalArgumentException("diff scope 只支持 unstaged、staged、all");
        }
        if ("staged".equals(scope)) {
            command.add("--cached");
        } else if ("all".equals(scope)) {
            command.add("HEAD");
        }
        if (boolArg(arguments, "stat", true)) {
            command.add("--stat");
        }
        String target = stringArg(arguments, "target", "");
        if (!target.isBlank()) {
            rejectUnsafeArg(target, "target");
            command.add(target);
        }
        String path = stringArg(arguments, "path", "");
        if (!path.isBlank() && !".".equals(path)) {
            List<String> paths = normalizeDiffPaths(List.of(path), gitWorkspace);
            command.add("--");
            command.addAll(paths);
        }
        return command;
    }

    private List<String> normalizeDiffPaths(List<String> values, Path gitWorkspace) {
        var result = new ArrayList<String>();
        for (String value : values) {
            String path = value == null ? "" : value.strip();
            if (path.isBlank() || ".".equals(path) || "./".equals(path)) {
                continue;
            }
            rejectUnsafeArg(path, "path");
            Path candidate = Path.of(path);
            Path resolved = candidate.isAbsolute()
                    ? candidate.toAbsolutePath().normalize()
                    : gitWorkspace.resolve(candidate).normalize();
            if (!resolved.startsWith(gitWorkspace)) {
                throw new IllegalArgumentException("path 路径越界: " + path);
            }
            String relative = gitWorkspace.relativize(resolved).toString();
            if (!relative.isBlank()) {
                result.add(relative);
            }
        }
        return List.copyOf(result);
    }

    private static String maybeTruncateDiffOutput(String action, String output, Map<String, Object> payload) {
        if (!"diff".equals(action) || output == null || output.length() <= MAX_DIFF_OUTPUT_CHARS) {
            return output;
        }
        payload.put("truncated", true);
        payload.put("original_output_chars", output.length());
        return output.substring(0, MAX_DIFF_OUTPUT_CHARS)
                + "\n\n[diff output truncated; ask for a specific file diff if more detail is needed]\n";
    }

    private List<String> buildShowCommand(Map<String, Object> arguments) {
        String target = stringArg(arguments, "target", "HEAD");
        rejectUnsafeArg(target, "target");
        return List.of("git", "show", "--stat", "--summary", target);
    }

    private List<String> buildFetchCommand(Map<String, Object> arguments) {
        String remote = remoteArg(arguments);
        return List.of("git", "fetch", remote);
    }

    private static boolean isWriteAction(String action) {
        return "add".equals(action)
                || "commit".equals(action)
                || "checkout_new_branch".equals(action)
                || "push".equals(action)
                || "pull".equals(action)
                || "restore".equals(action);
    }

    private String remoteArg(Map<String, Object> arguments) {
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

    private String branchArg(Map<String, Object> arguments, String missingMessage) {
        String branch = stringArg(arguments, "branch", "");
        if (branch.isBlank()) {
            throw new IllegalArgumentException(missingMessage);
        }
        rejectUnsafeBranch(branch);
        return branch;
    }

    private List<String> pathArgs(Map<String, Object> arguments, Path gitWorkspace) {
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
            Path candidate = Path.of(path);
            Path resolved = candidate.isAbsolute()
                    ? candidate.toAbsolutePath().normalize()
                    : gitWorkspace.resolve(candidate).normalize();
            if (!resolved.startsWith(gitWorkspace)) {
                throw new IllegalArgumentException("paths 路径越界: " + path);
            }
            if (resolved.equals(gitWorkspace)) {
                throw new IllegalArgumentException("paths 不能指向 GitTool 工作根目录");
            }
            String relative = gitWorkspace.relativize(resolved).toString();
            if (!relative.isBlank()) {
                result.add(relative);
            }
        }
        return List.copyOf(result);
    }

    private static List<String> commandWithPaths(List<String> prefix, List<String> paths) {
        var command = new ArrayList<String>(prefix);
        command.addAll(paths);
        return command;
    }

    private String writeResult(String action, Path cwd, List<GitResult> results, List<List<String>> commands) {
        return toJson(baseWritePayload(action, cwd, results, commands));
    }

    private LinkedHashMap<String, Object> baseWritePayload(String action, Path cwd, List<GitResult> results,
                                                           List<List<String>> commands) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("action", action);
        payload.put("cwd", cwd.toString());
        payload.put("commands", commands);
        var steps = new ArrayList<Map<String, Object>>();
        boolean failed = false;
        for (int i = 0; i < results.size(); i++) {
            GitResult result = results.get(i);
            var step = new LinkedHashMap<String, Object>();
            step.put("command", i < commands.size() ? commands.get(i) : List.of());
            step.put("exit_code", result.exitCode());
            step.put("timed_out", result.timedOut());
            step.put("output", result.output());
            steps.add(step);
            if (result.exitCode() != 0 || result.timedOut()) {
                failed = true;
            }
        }
        payload.put("steps", steps);
        payload.put("success", !failed);
        if (failed) {
            payload.put("error", "Git 本地写操作执行失败");
        }
        return payload;
    }

    private static void rejectUnsafeCommitMessage(String message) {
        if (message.contains("\u0000") || message.contains("\r")) {
            throw new IllegalArgumentException("commit message 包含非法控制字符");
        }
        if (message.length() > 500) {
            throw new IllegalArgumentException("commit message 过长，最多 500 字符");
        }
    }

    private static void rejectUnsafeBranch(String branch) {
        rejectUnsafeArg(branch, "branch");
        if (branch.contains(" ") || branch.contains("\\") || branch.contains("..")
                || branch.contains("@{") || branch.contains("//")
                || branch.startsWith("/") || branch.endsWith("/")) {
            throw new IllegalArgumentException("branch 名称不安全或不符合 Git 分支命名习惯");
        }
    }

    //该path是LLM输出的
    private Path workspaceRoot(ToolContext ctx) {
        if (ctx == null || ctx.effectiveWorkspaceDir() == null || ctx.effectiveWorkspaceDir().isBlank()) {
            return defaultGitWorkspace;
        }
        return Path.of(ctx.effectiveWorkspaceDir()).toAbsolutePath().normalize();
    }

    private Path resolvePath(Path gitWorkspace, String path) throws IOException {
        Path candidate = Path.of(path == null || path.isBlank() ? "." : path);
        Path resolved = candidate.isAbsolute()
                ? candidate.toAbsolutePath().normalize()
                : gitWorkspace.resolve(candidate).normalize();
        if (!resolved.startsWith(gitWorkspace)) {
            throw new IOException("路径越界，禁止在 GitTool 工作根目录之外执行: " + resolved);
        }
        if (!Files.exists(resolved)) {
            throw new IOException("路径不存在: " + resolved);
        }
        return Files.isDirectory(resolved) ? resolved : resolved.getParent();
    }

    private GitResult runGit(List<String> command, Path cwd) {
        var output = new StringBuilder();
        try {
            var pb = new ProcessBuilder(command);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            var process = pb.start();
            Thread reader = Thread.startVirtualThread(() -> readOutput(process, output));
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(Duration.ofSeconds(1).toMillis());
                //-1就是超时了
                return new GitResult(-1, output.toString(), true);
            }
            reader.join(Duration.ofSeconds(1).toMillis());
            //正常返回Git运行后的结果
            return new GitResult(process.exitValue(), output.toString(), false);
        } catch (Exception e) {
            return new GitResult(-1, "Git 命令执行异常: " + e.getMessage(), false);
        }
    }

    private static void readOutput(Process process, StringBuilder output) {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    if (output.length() < MAX_OUTPUT_CHARS) {
                        output.append(line).append('\n');
                    }
                }
            }
        } catch (IOException ignored) {
            // 超时或进程退出时流可能关闭，返回已读取内容即可。
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"JSON 序列化失败\"}";
        }
    }

    private static void rejectUnsafeArg(String value, String name) {
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

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        if (args == null) {
            return fallback;
        }
        Object value = args.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean boolArg(Map<String, Object> args, String key, boolean fallback) {
        if (args == null) {
            return fallback;
        }
        Object value = args.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    //这里定义了gitresult格式
    private record GitResult(int exitCode, String output, boolean timedOut) {}

}
