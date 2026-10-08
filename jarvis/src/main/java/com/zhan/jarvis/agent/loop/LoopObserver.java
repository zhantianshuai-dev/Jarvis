package com.zhan.jarvis.agent.loop;

import com.zhan.jarvis.llm.ToolCall;

import java.util.Map;

/**
 * AgentLoop 事件观察器。
 * 普通 HTTP 使用 NOOP；SSE 使用 SseLoopObserver 输出事件。
 */
public interface LoopObserver {

    LoopObserver NOOP = new LoopObserver() {
    };

    default void onToken(LoopState state, int iteration, String token) {
    }

    default void onReasoning(LoopState state, int iteration, String reasoning) {
    }

    default void onProviderEvent(LoopState state, int iteration, String type, String content,
                                 Map<String, Object> metadata) {
    }

    default void onToolCall(LoopState state, int iteration, ToolCall toolCall) {
    }

    default void onToolResult(LoopState state, int iteration, ToolResult result) {
    }

    default void onPlanUpdate(LoopState state, Map<String, Object> planPayload, Map<String, Object> todoPayload) {
    }

    default void onDone(LoopState state, LoopOutcome outcome) {
    }

    default void onInterrupted(LoopState state, int iteration, String reason) {
    }
}
