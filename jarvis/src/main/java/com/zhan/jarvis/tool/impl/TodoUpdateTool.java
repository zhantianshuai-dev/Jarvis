package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.todo.TodoManager;
import com.zhan.jarvis.tool.McpTool;
import com.zhan.jarvis.tool.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 更新 Agent 当前任务计划。
 */
public class TodoUpdateTool implements McpTool {

    private final ObjectMapper mapper;
    private final TodoManager todoManager;

    public TodoUpdateTool(ObjectMapper mapper, TodoManager todoManager) {
        this.mapper = mapper;
        this.todoManager = todoManager;
    }

    @Override
    public String name() {
        return "todo_update";
    }

    @Override
    public String description() {
        return "维护当前复杂任务的 Todo 列表。开始复杂任务、切换步骤、完成步骤或失败时调用；普通问答不要调用。";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = mapper.createObjectNode().put("type", "object");
        var props = schema.putObject("properties");
        var items = props.putObject("items")
                .put("type", "array")
                .put("minItems", 1)
                .put("maxItems", 20)
                .put("description", "完整 Todo 列表快照。每次更新都传入当前全部计划项。");
        var item = items.putObject("items").put("type", "object");
        var itemProps = item.putObject("properties");
        itemProps.putObject("id")
                .put("type", "string")
                .put("description", "稳定 ID。已有计划项更新状态时必须沿用原 ID；新增项可省略。");
        itemProps.putObject("content")
                .put("type", "string")
                .put("description", "简短任务内容，不超过一句话。");
        var status = itemProps.putObject("status")
                .put("type", "string")
                .put("description", "任务状态。同一时间最多一个 in_progress。");
        status.putArray("enum").add("pending").add("in_progress").add("completed").add("failed");
        itemProps.putObject("order")
                .put("type", "integer")
                .put("description", "显示顺序，从 1 开始。");
        item.putArray("required").add("content").add("status");
        schema.putArray("required").add("items");
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) arguments.get("items");
            String runId = String.valueOf(ctx.metadata().getOrDefault("run_id", ""));
            var state = todoManager.update(ctx.sessionId(), runId, items);
            var result = new LinkedHashMap<String, Object>();
            result.put("tool", name());
            result.put("success", true);
            result.put("summary", "Todo 状态已更新。");
            result.put("todos", todoManager.payload(state));
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            try {
                return mapper.writeValueAsString(Map.of(
                        "tool", name(),
                        "success", false,
                        "error", "更新 Todo 失败: " + e.getMessage()
                ));
            } catch (Exception ignored) {
                return "{\"tool\":\"todo_update\",\"success\":false,\"error\":\"更新 Todo 失败\"}";
            }
        }
    }
}
