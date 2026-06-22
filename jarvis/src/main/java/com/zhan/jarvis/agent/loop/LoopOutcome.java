package com.zhan.jarvis.agent.loop;

/**
 * AgentLoop 结束状态。
 */
public record LoopOutcome(
        String sessionId,
        String reply,
        int iteration,
        String finishReason,
        boolean requiresConfirmation,
        boolean maxIterationsReached
) {
}
