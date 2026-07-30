package com.zhan.jarvis.llm;

import java.util.List;

/**
 * 从单个 SSE 事件中解析出的 OpenAI 兼容流式响应片段。
 */
public record ChatStreamDelta(
        String content,
        String reasoningContent,
        List<ToolCallDelta> toolCallDeltas,
        String finishReason,
        ChatResponse.TokenUsage usage,
        boolean done,
        LlmProviderEvent providerEvent
) {
    public static ChatStreamDelta doneEvent() {
        return new ChatStreamDelta(null, null, List.of(), null, null, true, null);
    }

    public static ChatStreamDelta providerEvent(LlmProviderEvent event) {
        return new ChatStreamDelta(null, null, List.of(), null, null, false, event);
    }

    public record ToolCallDelta(
            int index,
            String id,
            String name,
            String arguments
    ) {}
}
