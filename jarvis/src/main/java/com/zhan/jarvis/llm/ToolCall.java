package com.zhan.jarvis.llm;

/**
 * LLM 返回的工具调用请求。
 *
 * @param id        tool_call 唯一 ID（需要原样传回 tool 消息中）
 * @param name      工具名称
 * @param arguments JSON 格式的参数
 */
public record ToolCall(
    String id,
    String name,
    String arguments
) {}