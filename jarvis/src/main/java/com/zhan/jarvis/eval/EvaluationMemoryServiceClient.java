package com.zhan.jarvis.eval;

import com.zhan.jarvis.config.JarvisConfig;
import com.zhan.jarvis.memory.MemoryServiceClient;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评测专用内存实现。
 * 真实 AgentLoop 仍经过 SessionManager 和 ContextBuilder，但不会写入或请求 memory-service。
 */
final class EvaluationMemoryServiceClient extends MemoryServiceClient {

    private final Map<String, List<SessionContext.MessageStub>> sessions = new ConcurrentHashMap<>();

    EvaluationMemoryServiceClient(JarvisConfig.MemoryServiceConfig config, WebClient.Builder builder,
                                  ObjectMapper objectMapper) {
        super(config, builder, objectMapper);
    }

    @Override
    public void createSession(String sessionId, String ownerUserId) {
        sessions.computeIfAbsent(sessionId, ignored -> new ArrayList<>());
    }

    @Override
    public void addMessage(String sessionId, String role, String text, Map<String, Object> metadata) {
        sessions.computeIfAbsent(sessionId, ignored -> new ArrayList<>())
                .add(new SessionContext.MessageStub(role == null ? "" : role, text == null ? "" : text));
    }

    @Override
    public String search(String query, int limit) {
        return "";
    }

    @Override
    public String write(String content, String contextType) {
        return "{\"stored\":false,\"mode\":\"evaluation\"}";
    }

    @Override
    public String commitSession(String sessionId, int keepRecentCount) {
        return "{\"committed\":false,\"mode\":\"evaluation\"}";
    }

    @Override
    public SessionContext getSessionContext(String sessionId, int maxMessages) {
        List<SessionContext.MessageStub> messages = sessions.getOrDefault(sessionId, List.of());
        int from = Math.max(0, messages.size() - Math.max(0, maxMessages));
        return new SessionContext("", List.copyOf(messages.subList(from, messages.size())), messages.size(), 0);
    }

    @Override
    public List<SessionSummary> listSessions(String ownerUserId) {
        String now = Instant.now().toString();
        return sessions.entrySet().stream()
                .map(entry -> new SessionSummary(entry.getKey(), "Evaluation session", entry.getValue().size(), now, now))
                .toList();
    }

    @Override
    public boolean deleteSession(String sessionId, String ownerUserId) {
        sessions.remove(sessionId);
        return true;
    }
}
