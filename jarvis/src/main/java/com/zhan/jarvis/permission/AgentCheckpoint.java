package com.zhan.jarvis.permission;

import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.llm.Message;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * AgentLoop 人工确认中断点。
 * 第一版使用内存存储，保存恢复执行所需的最小状态。
 */
public record AgentCheckpoint(
        String confirmId,
        String pendingToolCallId,
        SessionKey sessionKey,
        String sessionId,
        String userId,
        List<Message> messages,
        int iteration,
        Map<String, Object> metadata,
        Instant expiresAt
) {
}
