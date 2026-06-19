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
        return "派生子 Agent 在后台执行独立任务。用于处理大型、独立的子任务。" +
               "子 Agent 会自行完成并返回结果。代码修改类并行任务可设置 create_worktree=true 绑定独立 Git worktree。";
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
        var handle = subagentManager.spawn(task, ctx.sessionId(), ctx.sessionKey(), ctx.userId(),
                ctx.metadata(), createWorktree, worktree);
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
        sb.append("子 Agent 正在后台执行，完成后结果将可用。");
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
}
