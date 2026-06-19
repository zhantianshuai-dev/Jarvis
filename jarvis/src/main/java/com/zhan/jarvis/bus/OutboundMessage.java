package com.zhan.jarvis.bus;

import com.zhan.jarvis.channel.SessionKey;

import java.time.Instant;
import java.util.Map;

/**
 * Agent 处理完成后的统一输出消息。
 */
public record OutboundMessage(
        String inboundId,
        SessionKey sessionKey,
        String sessionId,
        String userId,
        String content,
        Map<String, Object> metadata,
        Instant createdAt
) {
    public static OutboundMessage of(String inboundId, String sessionId, String userId,
                                     String content, Map<String, Object> metadata) {
        return of(inboundId, new SessionKey("http", "default", sessionId), sessionId, userId, content, metadata);
    }

    public static OutboundMessage of(String inboundId, SessionKey sessionKey, String sessionId, String userId,
                                     String content, Map<String, Object> metadata) {
        return new OutboundMessage(
                inboundId,
                sessionKey,
                sessionId,
                userId,
                content,
                metadata == null ? Map.of() : new java.util.LinkedHashMap<>(metadata),
                Instant.now()
        );
    }
}
