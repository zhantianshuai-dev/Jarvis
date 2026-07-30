package com.zhan.jarvis.llm;

import java.util.List;

/**
 * LLM 聊天响应。
 *
 * @param content    文本回复（无工具调用时）
 * @param toolCalls  工具调用列表（有工具调用时）
 * @param finishReason 结束原因，如 stop / tool_calls / length
 * @param usage       Token 使用统计
 */
public record ChatResponse(
    String content,
    List<ToolCall> toolCalls,
    String finishReason,
    TokenUsage usage,
    String reasoningContent
) {
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {}
}
