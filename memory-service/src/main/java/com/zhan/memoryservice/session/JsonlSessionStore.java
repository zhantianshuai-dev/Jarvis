package com.zhan.memoryservice.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSONL 文件会话存储 — 对标 Jarvis messages.jsonl + .meta.json。
 *
 * <pre>
 * {workspace}/sessions/{sessionId}/
 * ├── messages.jsonl    ← 一行一条消息（JSON）
 * └── .meta.json        ← 会话元数据
 * </pre>
 */
public class JsonlSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(JsonlSessionStore.class);

    private final Path sessionsDir;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, Session> cache = new ConcurrentHashMap<>();

    public JsonlSessionStore(Path workspaceDir, ObjectMapper json) {
        this.sessionsDir = workspaceDir.resolve("sessions");
        this.json = json;
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建会话目录: " + sessionsDir, e);
        }
        log.info("JsonlSessionStore 初始化: {}", sessionsDir.toAbsolutePath());
    }

    @Override
    public Session createOrGet(String sessionId, int keepRecentCount) {
        return cache.computeIfAbsent(sessionId, id -> {
            var loaded = loadSession(id);
            if (loaded != null) {
                log.debug("从磁盘加载会话: {} ({} 条消息)", id, loaded.messageCount());
                return loaded;
            }
            var s = Session.createNew(id, keepRecentCount);
            saveMeta(s);
            log.debug("创建新会话: {}", id);
            return s;
        });
    }

    @Override
    public Session get(String sessionId) {
        var s = cache.get(sessionId);
        if (s != null) return s;
        var loaded = loadSession(sessionId);
        if (loaded != null) {
            cache.put(sessionId, loaded);
            return loaded;
        }
        throw new NoSuchElementException("Session not found: " + sessionId);
    }

    @Override
    public void update(Session s) {
        cache.put(s.sessionId(), s);
        saveMeta(s);
    }

    @Override
    public void addMessage(String sessionId, Message msg) {
        var session = cache.get(sessionId);
        if (session == null) {
            session = loadSession(sessionId);
            if (session != null) cache.put(sessionId, session);
        }

        Path msgFile = sessionDir(sessionId).resolve("messages.jsonl");
        try {
            String line = json.writeValueAsString(toMessageJson(msg)) + "\n";
            Files.createDirectories(msgFile.getParent());
            Files.writeString(msgFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("消息写入失败: " + msg.id(), e);
        }
    }

    @Override
    public List<Message> getMessages(String sessionId) {
        return readAllMessages(sessionId);
    }

    @Override
    public List<SessionSummary> listSessions() {
        try (var stream = Files.list(sessionsDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(path -> loadSession(path.getFileName().toString()))
                    .filter(Objects::nonNull)
                    .map(s -> new SessionSummary(
                            s.sessionId(),
                            s.ownerUserId(),
                            s.title(),
                            messageCount(s.sessionId()),
                            s.totalTurns(),
                            s.compressionCount(),
                            s.createdAt(),
                            s.updatedAt()))
                    .sorted(Comparator.comparing(SessionSummary::updatedAt).reversed())
                    .toList();
        } catch (IOException e) {
            log.warn("列出会话失败: {}", sessionsDir, e);
            return List.of();
        }
    }

    @Override
    public List<Message> getRecentMessages(String sessionId, int n) {
        var all = readAllMessages(sessionId);
        if (all.size() <= n) return all;
        return all.subList(all.size() - n, all.size());
    }

    @Override
    public void deleteMessages(String sessionId, List<String> messageIds) {
        if (messageIds.isEmpty()) return;
        var ids = new HashSet<>(messageIds);
        var all = readAllMessages(sessionId);
        var kept = all.stream().filter(m -> !ids.contains(m.id())).toList();

        Path msgFile = sessionDir(sessionId).resolve("messages.jsonl");
        try {
            Files.createDirectories(msgFile.getParent());
            var sb = new StringBuilder();
            for (var msg : kept) {
                sb.append(json.writeValueAsString(toMessageJson(msg))).append("\n");
            }
            Files.writeString(msgFile, sb.toString());
        } catch (IOException e) {
            throw new RuntimeException("消息删除失败: sessionId=" + sessionId, e);
        }
    }

    @Override
    public int messageCount(String sessionId) {
        return readAllMessages(sessionId).size();
    }

    // ---- internal ----

    private Path sessionDir(String sessionId) {
        return sessionsDir.resolve(sessionId);
    }

    private Session loadSession(String sessionId) {
        Path dir = sessionDir(sessionId);
        Path msgFile = dir.resolve("messages.jsonl");
        Path metaFile = dir.resolve(".meta.json");

        if (!Files.exists(msgFile)) return null;

        try {
            int msgCount = 0;
            if (Files.exists(msgFile)) {
                msgCount = (int) Files.lines(msgFile).filter(l -> !l.isBlank()).count();
            }

            if (Files.exists(metaFile)) {
                var node = json.readTree(metaFile.toFile());
                return new Session(
                        sessionId,
                        msgCount,
                        node.has("totalTurns") ? node.get("totalTurns").asInt() : 0,
                        node.has("compressionCount") ? node.get("compressionCount").asInt() : 0,
                        node.has("keepRecentCount") ? node.get("keepRecentCount").asInt() : 10,
                        node.has("pendingTokens") ? node.get("pendingTokens").asInt() : 0,
                        node.has("ownerUserId") && !node.get("ownerUserId").isNull()
                                ? node.get("ownerUserId").asText() : "",
                        node.has("title") && !node.get("title").isNull()
                                ? node.get("title").asText() : "",
                        node.has("createdAt") ? Instant.parse(node.get("createdAt").asText()) : Instant.now(),
                        node.has("updatedAt") ? Instant.parse(node.get("updatedAt").asText()) : Instant.now()
                );
            }

            return new Session(sessionId, msgCount, 0, 0, 10, 0,
                    "", "", Instant.now(), Instant.now());
        } catch (Exception e) {
            log.warn("加载会话失败: {}, 将创建新会话", sessionId, e);
            return null;
        }
    }

    private void saveMeta(Session s) {
        try {
            var meta = json.createObjectNode();
            meta.put("sessionId", s.sessionId());
            meta.put("messageCount", s.messageCount());
            meta.put("totalTurns", s.totalTurns());
            meta.put("compressionCount", s.compressionCount());
            meta.put("keepRecentCount", s.keepRecentCount());
            meta.put("pendingTokens", s.pendingTokens());
            meta.put("ownerUserId", s.ownerUserId() != null ? s.ownerUserId() : "");
            meta.put("title", s.title() != null ? s.title() : "");
            meta.put("createdAt", s.createdAt().toString());
            meta.put("updatedAt", s.updatedAt().toString());

            Path metaFile = sessionDir(s.sessionId()).resolve(".meta.json");
            Files.createDirectories(metaFile.getParent());
            Files.writeString(metaFile, json.writeValueAsString(meta));
        } catch (IOException e) {
            log.warn("保存 .meta.json 失败: {}", s.sessionId(), e);
        }
    }

    private List<Message> readAllMessages(String sessionId) {
        Path msgFile = sessionDir(sessionId).resolve("messages.jsonl");
        if (!Files.exists(msgFile)) return List.of();

        try {
            return Files.lines(msgFile)
                    .filter(l -> !l.isBlank())
                    .map(this::parseMessage)
                    .toList();
        } catch (IOException e) {
            log.warn("读取消息失败: {}", sessionId, e);
            return List.of();
        }
    }

    private Message parseMessage(String line) {
        try {
            var node = json.readTree(line);
            var partsRaw = json.treeToValue(node.get("parts"), List.class);
            var parts = new ArrayList<Message.Part>();
            for (var p : partsRaw) {
                @SuppressWarnings("unchecked")
                var m = (Map<String, Object>) p;
                parts.add(new Message.Part(
                        (String) m.get("type"),
                        (String) m.get("text"),
                        (String) m.get("abstractText")
                ));
            }
            return new Message(
                    node.get("id").asText(),
                    node.get("role").asText(),
                    parts,
                    node.has("roleId") && !node.get("roleId").isNull()
                            ? node.get("roleId").asText() : null,
                    node.has("metadata") && node.get("metadata").isObject()
                            ? json.treeToValue(node.get("metadata"), Map.class) : Map.of(),
                    node.has("estimatedTokens") ? node.get("estimatedTokens").asInt() : 0,
                    node.has("createdAt") ? Instant.parse(node.get("createdAt").asText())
                            : Instant.now()
            );
        } catch (Exception e) {
            throw new RuntimeException("消息 JSON 解析失败: " + line, e);
        }
    }

    /** 将 Message 转为 JSON 序列化友好的 Map */
    private Map<String, Object> toMessageJson(Message msg) {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", msg.id());
        map.put("role", msg.role());
        map.put("parts", msg.parts().stream()
                .map(p -> Map.of("type", p.type(), "text", p.text() != null ? p.text() : "",
                        "abstractText", p.abstractText() != null ? p.abstractText() : ""))
                .toList());
        map.put("roleId", msg.roleId());
        map.put("metadata", msg.metadata() != null ? msg.metadata() : Map.of());
        map.put("estimatedTokens", msg.estimatedTokens());
        map.put("createdAt", msg.createdAt().toString());
        return map;
    }
}
