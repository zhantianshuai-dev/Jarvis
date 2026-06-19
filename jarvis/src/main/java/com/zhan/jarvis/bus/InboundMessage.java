package com.zhan.jarvis.bus;

import com.zhan.jarvis.channel.SessionKey;

import java.time.Instant;
import java.util.Map;

/**
 * 进入 Agent 的统一消息。
 */
public record InboundMessage(
        String id,
        SessionKey sessionKey,
        String sessionId,
        String userId,
        String content,
        Map<String, Object> metadata,
        Instant createdAt
) {
    public static InboundMessage of(String id, String sessionId, String userId, String content) {
        return of(id, new SessionKey("http", "default", sessionId), sessionId, userId, content);
    }

    public static InboundMessage of(String id, SessionKey sessionKey, String sessionId,
                                    String userId, String content) {
        return of(id, sessionKey, sessionId, userId, content, Map.of());
    }

    public static InboundMessage of(String id, SessionKey sessionKey, String sessionId,
                                    String userId, String content, Map<String, Object> metadata) {
        return new InboundMessage(id, sessionKey, sessionId, userId, content,
                metadata == null ? Map.of() : new java.util.LinkedHashMap<>(metadata),
                Instant.now());
    }
}
