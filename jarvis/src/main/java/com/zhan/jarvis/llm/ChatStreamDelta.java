package com.zhan.jarvis.llm;

import java.util.List;

/**
 * OpenAI-compatible streaming chunk parsed from one SSE event.
 */
public record ChatStreamDelta(
        String content,
        String reasoningContent,
        List<ToolCallDelta> toolCallDeltas,
        String finishReason,
        ChatResponse.TokenUsage usage,
        boolean done
) {
    public static ChatStreamDelta doneEvent() {
        return new ChatStreamDelta(null, null, List.of(), null, null, true);
    }

    public record ToolCallDelta(
            int index,
            String id,
            String name,
            String arguments
    ) {}
}
