package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.subagent.SubagentManager;
import com.zhan.jarvis.tool.McpTool;
import com.zhan.jarvis.tool.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 派生子 Agent 后台执行任务。
 * 对标 Python VikingBot spawn tool。
 */
public class SpawnTool implements McpTool {

    private final ObjectMapper mapper;
    private final SubagentManager subagentManager;

    public SpawnTool(ObjectMapper mapper, SubagentManager subagentManager) {
        this.mapper = mapper;
        this.subagentManager = subagentManager;
    }

    @Override public String name() { return "spawn"; }

    @Override public String description() {
        return "派生子 Agent 执行独立子任务。默认等待子 Agent 完成并把结果返回给主 Agent，用于拆解、并行执行、再综合。" +
               "只有明确需要后台异步执行时才设置 wait_for_completion=false。代码修改类并行任务可设置 create_worktree=true 绑定独立 Git worktree。";
    }

    @Override public JsonNode inputSchema() {
        var schema = mapper.createObjectNode()
                .put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("task")
                .put("type", "string")
                .put("description", "子 Agent 需要执行的任务描述");
        props.putObject("create_worktree")
                .put("type", "boolean")
                .put("description", "是否为该子 Agent 创建独立 Git worktree。默认 false；代码修改/长期并行任务建议 true。");
        props.putObject("worktree")
                .put("type", "string")
                .put("description", "可选 worktree 名称。create_worktree=true 时按该名称创建；false 时绑定已存在的 worktree。");
        props.putObject("wait_for_completion")
                .put("type", "boolean")
                .put("description", "是否等待子 Agent 完成并返回最终结果。默认 true；只有需要后台异步任务时才设为 false。");
        props.putObject("timeout_seconds")
                .put("type", "integer")
                .put("minimum", 10)
                .put("maximum", 300)
                .put("description", "等待子 Agent 完成的最长秒数。默认 300，仅 wait_for_completion=true 时生效。");
        schema.putArray("required").add("task");
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        String task = (String) arguments.get("task");
        if (task == null || task.isBlank()) {
            return "错误: 缺少 task 参数";
        }

        boolean createWorktree = boolArg(arguments, "create_worktree", false);
        String worktree = stringArg(arguments, "worktree", "");
        boolean waitForCompletion = boolArg(arguments, "wait_for_completion", true);
        long timeoutSeconds = longArg(arguments, "timeout_seconds", 300, 10, 300);
        var handle = subagentManager.spawn(task, ctx.sessionId(), ctx.sessionKey(), ctx.userId(),
                ctx.metadata(), createWorktree, worktree, !waitForCompletion);
        if (!handle.started()) {
            return "子 Agent 启动失败，任务 ID: " + handle.taskId() + "\n原因: " + handle.error();
        }

        var sb = new StringBuilder();
        sb.append("子 Agent 已启动，任务 ID: ").append(handle.taskId()).append('\n');
        sb.append("任务: ").append(task).append('\n');
        if (!handle.worktreePath().isBlank()) {
            sb.append("worktree: ").append(handle.worktreeName()).append('\n');
            sb.append("worktree_path: ").append(handle.worktreePath()).append('\n');
        }
        if (!waitForCompletion) {
            sb.append("子 Agent 正在后台执行，完成后结果将通过状态事件和会话消息返回。");
            return sb.toString();
        }

        var result = subagentManager.waitForCompletion(handle.taskId(), timeoutSeconds * 1000);
        if ("completed".equals(result.status())) {
            sb.append("子 Agent 执行成功。\n\n结果:\n").append(safe(result.result()));
            return sb.toString();
        }
        if ("failed".equals(result.status())) {
            var latest = subagentManager.getResult(handle.taskId());
            if (latest != null && "running".equals(latest.status())) {
                sb.append("等待子 Agent 超时（").append(timeoutSeconds).append(" 秒），任务仍在后台运行。完成后结果将通过状态事件返回。");
                return sb.toString();
            }
            sb.append("子 Agent 执行失败。\n\n错误: ").append(safe(result.error()));
            return sb.toString();
        }

        sb.append("子 Agent 状态: ").append(result.status());
        return sb.toString();
    }

    private static String stringArg(Map<String, Object> args, String key, String fallback) {
        Object value = args == null ? null : args.get(key);
        return value == null ? fallback : value.toString().trim();
    }

    private static boolean boolArg(Map<String, Object> args, String key, boolean fallback) {
        Object value = args == null ? null : args.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    private static long longArg(Map<String, Object> args, String key, long fallback, long min, long max) {
        Object value = args == null ? null : args.get(key);
        long parsed = fallback;
        if (value instanceof Number n) {
            parsed = n.longValue();
        } else if (value instanceof String s && !s.isBlank()) {
            try {
                parsed = Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
