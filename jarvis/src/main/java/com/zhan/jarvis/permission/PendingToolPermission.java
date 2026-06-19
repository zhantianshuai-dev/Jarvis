package com.zhan.jarvis.permission;

import com.zhan.jarvis.channel.SessionKey;

import java.time.Instant;
import java.util.Map;

/**
 * 一次等待人工确认的工具调用。
 */
public record PendingToolPermission(
        String confirmId,
        String toolName,
        Map<String, Object> arguments,
        String sessionId,
        SessionKey sessionKey,
        String workspaceDir,
        String requestedBy,
        Map<String, Object> metadata,
        String summary,
        Instant expiresAt
) {
}
