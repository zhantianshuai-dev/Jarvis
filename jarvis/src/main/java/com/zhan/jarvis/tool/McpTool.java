package com.zhan.jarvis.tool;

import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * MCP 工具接口 — 对标 MCP 协议的 Tool 定义。
 * <p>
 * 每个工具提供 name/description/inputSchema（用于 LLM function calling）
 * 和 execute（用于实际执行）。
 */
public interface McpTool {

    /** 工具名称（全局唯一） */
    String name();

    /** 工具描述（LLM 用于判断何时调用） */
    String description();

    /** JSON Schema 格式的输入参数定义 */
    JsonNode inputSchema();

    /** 执行工具，返回结果文本 */
    String execute(Map<String, Object> arguments, ToolContext ctx);
}