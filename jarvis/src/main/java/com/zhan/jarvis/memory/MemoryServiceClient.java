package com.zhan.jarvis.memory;

import com.zhan.jarvis.config.JarvisConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * memory-service HTTP 客户端 — 封装对 memory-service 的 API 调用。
 */
public class MemoryServiceClient {

    private static final Logger log = LoggerFactory.getLogger(MemoryServiceClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public MemoryServiceClient(JarvisConfig.MemoryServiceConfig config, WebClient.Builder builder,
                               ObjectMapper objectMapper) {
        this.webClient = builder
                .baseUrl(stripTrailingSlash(config.baseUrl()))
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.objectMapper = objectMapper;
        log.info("MemoryServiceClient 已创建: baseUrl={}", config.baseUrl());
    }

    /** 语义检索记忆/资源/技能 */
    public String search(String query, int limit) {
        return webClient.post()
                .uri("/api/v1/search")
                .bodyValue(Map.of("query", query, "limit", limit))
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /** 写入新记忆 */
    public String write(String content, String contextType) {
        return webClient.post()
                .uri("/api/v1/content/write")
                .bodyValue(Map.of("content", content, "contextType", contextType, "source", "jarvis"))
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /** 创建会话 */
    public void createSession(String sessionId) {
        createSession(sessionId, "");
    }

    /** 创建会话，并写入归属用户 */
    public void createSession(String sessionId, String ownerUserId) {
        webClient.post()
                .uri("/api/v1/session/create")
                .bodyValue(Map.of(
                        "session_id", sessionId,
                        "owner_user_id", ownerUserId != null ? ownerUserId : ""))
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /** 追加消息到会话 */
    public void addMessage(String sessionId, String role, String text) {
        addMessage(sessionId, role, text, Map.of());
    }

    /** 追加消息到会话，支持附加 metadata 写入 memory-service JSONL。 */
    public void addMessage(String sessionId, String role, String text, Map<String, Object> metadata) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("session_id", sessionId);
        body.put("role", role);
        body.put("text", text != null ? text : "");
        body.put("metadata", metadata != null ? metadata : Map.of());
        webClient.post()
                .uri("/api/v1/session/message")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /** 触发 memory-service 对会话进行归档、working memory 生成和记忆提取。 */
    public String commitSession(String sessionId, int keepRecentCount) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("session_id", sessionId);
        if (keepRecentCount >= 0) {
            body.put("keep_recent_count", keepRecentCount);
        }
        return webClient.post()
                .uri("/api/v1/session/commit")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    public List<SessionSummary> listSessions(String ownerUserId) {
        String json = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/session/list")
                        .queryParam("owner_user_id", ownerUserId != null ? ownerUserId : "")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .block();
        if (json == null || json.isBlank()) return List.of();

        try {
            var node = objectMapper.readTree(json);
            var sessions = new ArrayList<SessionSummary>();
            if (node.has("sessions")) {
                for (var item : node.get("sessions")) {
                    sessions.add(new SessionSummary(
                            text(item, "session_id"),
                            text(item, "title"),
                            item.has("message_count") ? item.get("message_count").asInt() : 0,
                            text(item, "created_at"),
                            text(item, "updated_at")));
                }
            }
            return sessions;
        } catch (Exception e) {
            log.warn("解析 session list 失败: {}", e.getMessage());
            return List.of();
        }
    }

    public SessionMessages getSessionMessages(String sessionId, int maxMessages) {
        String json = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/session/{id}/messages")
                        .queryParam("max_messages", maxMessages)
                        .build(sessionId))
                .retrieve()
                .bodyToMono(String.class)
                .block();
        if (json == null || json.isBlank()) {
            return new SessionMessages(sessionId, "", "新的对话", List.of());
        }

        try {
            var node = objectMapper.readTree(json);
            var messages = new ArrayList<SessionMessage>();
            if (node.has("messages")) {
                for (var item : node.get("messages")) {
                    messages.add(new SessionMessage(
                            text(item, "id"),
                            text(item, "role"),
                            text(item, "content"),
                            readMetadata(item),
                            text(item, "created_at")));
                }
            }
            return new SessionMessages(
                    text(node, "session_id", sessionId),
                    text(node, "owner_user_id"),
                    text(node, "title", "新的对话"),
                    messages);
        } catch (Exception e) {
            log.warn("解析 session messages 失败: {}", e.getMessage());
            return new SessionMessages(sessionId, "", "新的对话", List.of());
        }
    }

    /**
     * 获取会话上下文（对标 Jarvis get_session_context）。
     * 返回 working_memory + 最近 N 条消息。
     */
    public SessionContext getSessionContext(String sessionId, int maxMessages) {
        String json = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/session/{id}/context")
                        .queryParam("max_messages", maxMessages)
                        .build(sessionId))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (json == null || json.isBlank()) return SessionContext.EMPTY;

        try {
            var node = objectMapper.readTree(json);
            String wm = node.has("working_memory") ? node.get("working_memory").asText() : "";

            var messages = new ArrayList<SessionContext.MessageStub>();
            if (node.has("messages")) {
                for (var msgNode : node.get("messages")) {
                    messages.add(new SessionContext.MessageStub(
                            msgNode.get("role").asText(),
                            msgNode.get("content").asText()));
                }
            }

            int msgCount = node.has("message_count") ? node.get("message_count").asInt() : 0;
            int compressionCount = node.has("compression_count") ? node.get("compression_count").asInt() : 0;

            return new SessionContext(wm, messages, msgCount, compressionCount);
        } catch (Exception e) {
            log.warn("解析 session context 失败: {}", e.getMessage());
            return SessionContext.EMPTY;
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String text(JsonNode node, String field) {
        return text(node, field, "");
    }

    private static String text(JsonNode node, String field, String fallback) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : fallback;
    }

    private Map<String, Object> readMetadata(JsonNode node) {
        if (!node.has("metadata") || !node.get("metadata").isObject()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = objectMapper.treeToValue(node.get("metadata"), Map.class);
            return metadata != null ? metadata : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 会话上下文 — 对标 Jarvis get_session_context 返回值。
     */
    public record SessionContext(
            String workingMemory,
            List<MessageStub> messages,
            int messageCount,
            int compressionCount
    ) {
        public static final SessionContext EMPTY = new SessionContext("", List.of(), 0, 0);

        public record MessageStub(String role, String content) {}
    }

    public record SessionSummary(
            String sessionId,
            String title,
            int messageCount,
            String createdAt,
            String updatedAt
    ) {}

    public record SessionMessages(
            String sessionId,
            String ownerUserId,
            String title,
            List<SessionMessage> messages
    ) {}

    public record SessionMessage(
            String id,
            String role,
            String content,
            Map<String, Object> metadata,
            String createdAt
    ) {}
}
