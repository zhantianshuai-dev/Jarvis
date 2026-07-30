package com.zhan.memoryservice.session;

import java.time.Instant;
import java.util.List;

/**
 * 会话模型，会话核心字段。
 * 会话代表一段对话的生命周期。
 */
public record Session(
    String sessionId,           // 会话唯一标识
    int messageCount,           // 当前 live 消息数
    int totalTurns,             // 总 user 消息轮次
    int compressionCount,       // 已归档次数
    int keepRecentCount,        // commit 时保留最近消息数（默认 10）
    int pendingTokens,          // 待归档消息的累计 token 数
    String ownerUserId,         // 会话归属用户，用于多用户隔离
    String title,               // 会话标题，默认取第一条用户消息
    Instant createdAt,
    Instant updatedAt
) {
    public static Session createNew(String sessionId, int keepRecentCount) {
        return createNew(sessionId, keepRecentCount, null);
    }

    public static Session createNew(String sessionId, int keepRecentCount, String ownerUserId) {
        var now = Instant.now();
        return new Session(sessionId, 0, 0, 0, keepRecentCount, 0,
                ownerUserId, "", now, now);
    }

    public Session withCommit(int newMessageCount, int newPendingTokens) {
        return new Session(sessionId, newMessageCount, totalTurns, compressionCount + 1,
                keepRecentCount, newPendingTokens, ownerUserId, title, createdAt, Instant.now());
    }

    public Session withMessageAdded(int msgTokens, boolean isUser) {
        // 滑动窗口：keep_recent_count == 0 → 每条消息贡献待归档 token。
        // keep_recent_count > 0 → 只有被推出窗口的消息才贡献待归档 token。
        int newPending = pendingTokens;
        if (keepRecentCount <= 0) {
            newPending += msgTokens;
        }
        // （简化：实际滑动窗口逻辑在会话服务中处理）
        return new Session(sessionId, messageCount + 1,
                isUser ? totalTurns + 1 : totalTurns,
                compressionCount, keepRecentCount, newPending, ownerUserId, title, createdAt, Instant.now());
    }

    public Session withOwner(String ownerUserId) {
        return new Session(sessionId, messageCount, totalTurns, compressionCount,
                keepRecentCount, pendingTokens, ownerUserId, title, createdAt, Instant.now());
    }

    public Session withTitle(String title) {
        return new Session(sessionId, messageCount, totalTurns, compressionCount,
                keepRecentCount, pendingTokens, ownerUserId, title, createdAt, Instant.now());
    }
}
