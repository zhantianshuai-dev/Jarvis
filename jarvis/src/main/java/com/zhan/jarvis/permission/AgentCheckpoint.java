package com.zhan.jarvis.permission;

import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.llm.Message;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AgentLoop 通用检查点。
 * 第一版仍使用内存存储，但结构上不再只服务人工确认，后续可扩展为持久化 checkpoint。
 */
public record AgentCheckpoint(
        String checkpointId,
        String runId,
        String confirmId,
        String pendingToolCallId,
        SessionKey sessionKey,
        String sessionId,
        String userId,
        List<Message> messages,
        int iteration,
        Map<String, Object> metadata,
        Map<String, Object> outputMetadata,
        Map<String, Object> tokenUsage,
        String runMode,
        Set<String> activeDeferredTools,
        Map<String, Object> pendingConfirmation,
        Instant createdAt,
        Instant expiresAt
) {
}
