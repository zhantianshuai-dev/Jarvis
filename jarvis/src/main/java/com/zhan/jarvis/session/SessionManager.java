package com.zhan.jarvis.session;

import com.zhan.jarvis.memory.MemoryServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 会话管理器 — 委托 memory-service 管理会话生命周期。
 * Jarvis 不自己持久化，所有消息通过 API 写入 memory-service 的 JSONL 存储。
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final MemoryServiceClient memoryClient;
    private final SessionFileSpaceManager fileSpaceManager;

    public SessionManager(MemoryServiceClient memoryClient) {
        this(memoryClient, null);
    }

    public SessionManager(MemoryServiceClient memoryClient, SessionFileSpaceManager fileSpaceManager) {
        this.memoryClient = memoryClient;
        this.fileSpaceManager = fileSpaceManager;
    }

    /** 获取或创建会话 */
    public Session getOrCreate(String sessionId) {
        ensureFileSpace(sessionId);
        memoryClient.createSession(sessionId);
        return new Session(sessionId);
    }

    /** 获取或创建带用户归属的会话 */
    public Session getOrCreate(String sessionId, String ownerUserId) {
        ensureFileSpace(sessionId);
        memoryClient.createSession(sessionId, ownerUserId);
        return new Session(sessionId);
    }

    /** 添加消息（通过 API 写入 memory-service JSONL） */
    public void addMessage(String sessionId, String role, String content) {
        memoryClient.addMessage(sessionId, role, content);
    }

    /** 添加带元数据的消息（通过 API 写入 memory-service JSONL） */
    public void addMessage(String sessionId, String role, String content, Map<String, Object> metadata) {
        memoryClient.addMessage(sessionId, role, content, metadata);
    }

    /** 触发 memory-service 会话归档和记忆巩固。 */
    public String commit(String sessionId, int keepRecentCount) {
        return memoryClient.commitSession(sessionId, keepRecentCount);
    }

    public List<MemoryServiceClient.SessionSummary> listSessions(String ownerUserId) {
        return memoryClient.listSessions(ownerUserId);
    }

    public MemoryServiceClient.SessionMessages getSessionMessages(String sessionId, int maxMessages) {
        return memoryClient.getSessionMessages(sessionId, maxMessages);
    }

    public boolean deleteSession(String sessionId, String ownerUserId) {
        return memoryClient.deleteSession(sessionId, ownerUserId);
    }

    public SessionFileSpaceManager.SessionFileSpace ensureFileSpace(String sessionId) {
        if (fileSpaceManager == null) {
            return null;
        }
        return fileSpaceManager.ensure(sessionId);
    }
}
