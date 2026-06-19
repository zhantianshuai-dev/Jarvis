package com.zhan.memoryservice.session;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 消息模型，对标 Python Message + Part。
 * 一条消息包含多个 Part（文本、工具调用、上下文引用）。
 */
public record Message(
    String id,                  // msg_{uuid}
    String role,                // user / assistant / system
    List<Part> parts,           // 消息内容片段
    String roleId,              // user_id 或 agent_id
    Map<String, Object> metadata,// 工具调用、来源等附加信息
    int estimatedTokens,        // token 估算
    Instant createdAt
) {
    public static Message of(String role, String text) {
        var now = Instant.now();
        String safeText = text != null ? text : "";
        int tokens = Math.max(1, safeText.length() / 4);
        return new Message(
                "msg_" + UUID.randomUUID().toString().substring(0, 12),
                role,
                List.of(new Part("text", safeText, null)),
                null,
                Map.of(),
                tokens,
                now
        );
    }

    public static Message of(String role, String text, String roleId) {
        return of(role, text, roleId, Map.of());
    }

    public static Message of(String role, String text, String roleId, Map<String, Object> metadata) {
        var now = Instant.now();
        String safeText = text != null ? text : "";
        int tokens = Math.max(1, safeText.length() / 4);
        return new Message(
                "msg_" + UUID.randomUUID().toString().substring(0, 12),
                role,
                List.of(new Part("text", safeText, null)),
                roleId,
                metadata == null ? Map.of() : new LinkedHashMap<>(metadata),
                tokens,
                now
        );
    }

    /**
     * Part 片段，对标 Python Part。
     * type: text | tool | context
     */
    public record Part(
        String type,            // text / tool / context
        String text,            // 文本内容
        String abstractText     // context 类型的摘要
    ) {}
}
