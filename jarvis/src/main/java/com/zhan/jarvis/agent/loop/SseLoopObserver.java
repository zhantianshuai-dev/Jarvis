package com.zhan.jarvis.agent.loop;

import com.zhan.jarvis.llm.ToolCall;
import com.zhan.jarvis.server.sse.SseEventTypes;
import reactor.core.publisher.FluxSink;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将 AgentLoop 事件转成 SSE 事件。
 */
public class SseLoopObserver implements LoopObserver {

    private final FluxSink<Map<String, Object>> sink;

    public SseLoopObserver(FluxSink<Map<String, Object>> sink) {
        this.sink = sink;
    }

    @Override
    public void onToken(LoopState state, int iteration, String token) {
        emit(SseEventTypes.TOKEN, state.sessionId(), token, "chat", Map.of("iteration", iteration));
    }

    @Override
    public void onReasoning(LoopState state, int iteration, String reasoning) {
        emit(SseEventTypes.REASONING, state.sessionId(), reasoning, "chat", Map.of("iteration", iteration));
    }

    @Override
    public void onToolCall(LoopState state, int iteration, ToolCall toolCall) {
        emit(SseEventTypes.TOOL_CALL, state.sessionId(), toolCall.name(), "chat", Map.of(
                "iteration", iteration,
                "tool_call_id", toolCall.id(),
                "tool_name", toolCall.name(),
                "arguments", toolCall.arguments()
        ));
    }

    @Override
    public void onToolResult(LoopState state, int iteration, ToolResult result) {
        emit(SseEventTypes.TOOL_RESULT, state.sessionId(), result.result(), "chat", Map.of(
                "iteration", iteration,
                "tool_call_id", result.toolCallId(),
                "tool_name", result.toolName()
        ));
    }

    @Override
    public void onDone(LoopOutcome outcome) {
        emit(SseEventTypes.DONE, outcome.sessionId(), outcome.reply(), "chat", Map.of(
                "iteration", outcome.iteration(),
                "finish_reason", outcome.finishReason(),
                "requires_confirmation", outcome.requiresConfirmation(),
                "max_iterations_reached", outcome.maxIterationsReached()
        ));
    }

    public void emit(String type, String sessionId, String content, String source, Map<String, Object> extra) {
        if (sink.isCancelled()) {
            return;
        }
        var event = new LinkedHashMap<String, Object>();
        event.put("type", type);
        event.put("session_id", sessionId);
        event.put("content", content == null ? "" : content);
        event.put("source", source == null ? "" : source);
        event.put("created_at", Instant.now().toString());
        if (extra != null && !extra.isEmpty()) {
            event.putAll(extra);
        }
        sink.next(event);
    }
}
