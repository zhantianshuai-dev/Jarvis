package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.memory.MemoryServiceClient;
import com.zhan.jarvis.tool.McpTool;
import com.zhan.jarvis.tool.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 从记忆系统检索相关上下文。
 * 调用 memory-service /api/v1/search。
 */
public class MemorySearchTool implements McpTool {

    private final ObjectMapper mapper;
    private final MemoryServiceClient memoryClient;

    public MemorySearchTool(ObjectMapper mapper, MemoryServiceClient memoryClient) {
        this.mapper = mapper;
        this.memoryClient = memoryClient;
    }

    @Override public String name() { return "memory_search"; }

    @Override public String description() {
        return "从记忆系统中检索与查询相关的记忆、资源和技能。" +
               "在回答用户问题前，应优先使用此工具获取上下文。" +
               "参数: query (搜索文本), limit (返回数量, 默认5)";
    }

    @Override public JsonNode inputSchema() {
        var schema = mapper.createObjectNode()
                .put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("query")
                .put("type", "string")
                .put("description", "搜索查询文本");
        props.putObject("limit")
                .put("type", "integer")
                .put("description", "返回记忆数量上限，默认 5");
        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        String query = (String) arguments.get("query");
        if (query == null || query.isBlank()) {
            return "错误: 缺少 query 参数";
        }
        int limit = 5;
        Object limitObj = arguments.get("limit");
        if (limitObj instanceof Number n) {
            limit = n.intValue();
        }

        try {
            String result = memoryClient.search(query, limit);
            if (result == null || result.isBlank() || result.equals("{}")) {
                return "记忆检索未找到相关结果。";
            }
            return result;
        } catch (Exception e) {
            return "记忆检索失败: " + e.getMessage();
        }
    }
}