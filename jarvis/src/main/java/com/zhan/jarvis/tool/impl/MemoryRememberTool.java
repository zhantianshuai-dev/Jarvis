package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.memory.MemoryServiceClient;
import com.zhan.jarvis.tool.McpTool;
import com.zhan.jarvis.tool.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 将重要信息写入记忆系统。
 * 调用 memory-service /api/v1/content/write。
 */
public class MemoryRememberTool implements McpTool {

    private final ObjectMapper mapper;
    private final MemoryServiceClient memoryClient;

    public MemoryRememberTool(ObjectMapper mapper, MemoryServiceClient memoryClient) {
        this.mapper = mapper;
        this.memoryClient = memoryClient;
    }

    @Override public String name() { return "memory_remember"; }

    @Override public String description() {
        return "将重要信息存入记忆系统，供后续检索。" +
               "当用户明确说'记住这个'或发现值得长期保存的信息时使用。" +
               "参数: content (要记住的内容), context_type (memory/resource/skill, 默认 memory)";
    }

    @Override public JsonNode inputSchema() {
        var schema = mapper.createObjectNode()
                .put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("content")
                .put("type", "string")
                .put("description", "要存入记忆的内容");
        props.putObject("context_type")
                .put("type", "string")
                .put("description", "类型: memory/resource/skill，默认 memory");
        schema.putArray("required").add("content");
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        String content = (String) arguments.get("content");
        if (content == null || content.isBlank()) {
            return "错误: 缺少 content 参数";
        }
        String contextType = (String) arguments.getOrDefault("context_type", "memory");

        try {
            String result = memoryClient.write(content, contextType);
            return "记忆已存入 (类型: " + contextType + "): " + result;
        } catch (Exception e) {
            return "记忆写入失败: " + e.getMessage();
        }
    }
}