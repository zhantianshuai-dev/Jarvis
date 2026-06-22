package com.zhan.jarvis.agent.loop;

import java.time.Instant;

/**
 * AgentLoop 中等待人工确认的工具调用。
 */
public record PendingConfirmation(
        ToolResult result,
        String reply,
        String confirmId,
        Instant expiresAt
) {
}
