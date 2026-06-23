package com.zhan.jarvis.llm;

import java.util.Map;

/**
 * 工具定义 — 对标 OpenAI function/tool 格式和 MCP tools/list 响应。
 *
 * @param name        工具名称
 * @param description 工具描述
 * @param inputSchema JSON Schema 格式的输入参数定义
 */
public record ToolDefinition(
    String name,
    String description,
    Map<String, Object> inputSchema,
    String group,
    String source,
    String schemaWeight,
    boolean deferred
) {
    public ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, inputSchema, "general", "local", "medium", false);
    }

    /** 转换为 OpenAI tools 格式 */
    public Map<String, Object> toOpenAiFormat() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", name,
                "description", description,
                "parameters", inputSchema
            )
        );
    }
}
