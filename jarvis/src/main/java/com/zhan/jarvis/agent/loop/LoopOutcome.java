package com.zhan.jarvis.agent.loop;

import java.util.Map;

/**
 * AgentLoop 结束状态。
 */
public record LoopOutcome(
        String sessionId,
        String reply,
        int iteration,
        String finishReason,
        boolean requiresConfirmation,
        boolean maxIterationsReached,
        Map<String, Object> tokenUsage
) {
}
