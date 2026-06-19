package com.zhan.memoryservice.session;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SessionStore — 会话和消息的持久化接口。
 */
public interface SessionStore {

    /** 创建或获取 Session */
    Session createOrGet(String sessionId, int keepRecentCount);

    /** 获取 Session 元数据 */
    Session get(String sessionId);

    /** 更新 Session 元数据 */
    void update(Session session);

    /** 追加一条消息 */
    void addMessage(String sessionId, Message msg);

    /** 获取指定会话的全部 live 消息（按时间排序） */
    List<Message> getMessages(String sessionId);

    /** 列出会话元数据 */
    List<SessionSummary> listSessions();

    /** 获取最近的 N 条消息 */
    List<Message> getRecentMessages(String sessionId, int n);

    /** 删除指定消息（commit 归档后） */
    void deleteMessages(String sessionId, List<String> messageIds);

    /** 获取消息总数 */
    int messageCount(String sessionId);

    record SessionSummary(
            String sessionId,
            String ownerUserId,
            String title,
            int messageCount,
            int totalTurns,
            int compressionCount,
            Instant createdAt,
            Instant updatedAt
    ) {}

    record SessionMessageView(
            String id,
            String role,
            String content,
            Map<String, Object> metadata,
            Instant createdAt
    ) {}
}
