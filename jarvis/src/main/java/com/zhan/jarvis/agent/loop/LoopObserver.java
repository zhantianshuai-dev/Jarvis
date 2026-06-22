package com.zhan.jarvis.agent.loop;

import com.zhan.jarvis.llm.ToolCall;

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

    default void onToolCall(LoopState state, int iteration, ToolCall toolCall) {
    }

    default void onToolResult(LoopState state, int iteration, ToolResult result) {
    }

    default void onDone(LoopOutcome outcome) {
    }
}
