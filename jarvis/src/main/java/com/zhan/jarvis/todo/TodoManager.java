package com.zhan.jarvis.todo;

import com.zhan.jarvis.concurrency.StripedLock;
import com.zhan.jarvis.session.SessionFileSpaceManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话级 Todo 状态管理。
 * Todo 只记录计划结构和状态，避免把大段任务过程写入聊天正文。
 */
public class TodoManager {

    private static final int MAX_ITEMS = 20;
    private static final int MAX_CONTENT_CHARS = 300;

    private final SessionFileSpaceManager fileSpaceManager;
    private final ObjectMapper objectMapper;
    private final StripedLock sessionLocks = new StripedLock(64);

    public TodoManager(SessionFileSpaceManager fileSpaceManager, ObjectMapper objectMapper) {
        this.fileSpaceManager = fileSpaceManager;
        this.objectMapper = objectMapper;
    }

    public TodoListState update(String sessionId, String runId, List<Map<String, Object>> rawItems)
            throws IOException {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IOException("缺少 sessionId");
        }
        if (rawItems == null || rawItems.isEmpty()) {
            throw new IOException("Todo 列表不能为空");
        }
        if (rawItems.size() > MAX_ITEMS) {
            throw new IOException("Todo 数量不能超过 " + MAX_ITEMS);
        }

        var lock = sessionLocks.forKey(sessionId);
        lock.lock();
        try {
            return updateLocked(sessionId, runId, rawItems);
        } finally {
            lock.unlock();
        }
    }

    private TodoListState updateLocked(String sessionId, String runId, List<Map<String, Object>> rawItems)
            throws IOException {
        var previous = loadUnlocked(sessionId);
        var previousById = new LinkedHashMap<String, TodoItem>();
        for (TodoItem item : previous.items()) {
            previousById.put(item.id(), item);
        }

        String now = Instant.now().toString();
        var items = new ArrayList<TodoItem>();
        for (int i = 0; i < rawItems.size(); i++) {
            Map<String, Object> raw = rawItems.get(i);
            String id = text(raw.get("id"));
            if (id.isBlank()) {
                id = "todo_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            }
            String content = truncate(text(raw.get("content")).strip());
            if (content.isBlank()) {
                throw new IOException("Todo 内容不能为空");
            }
            TodoStatus status = TodoStatus.from(raw.get("status"));
            int order = intValue(raw.get("order"), i + 1);
            TodoItem old = previousById.get(id);
            items.add(new TodoItem(
                    id,
                    content,
                    status,
                    order,
                    old != null && old.createdAt() != null && !old.createdAt().isBlank() ? old.createdAt() : now,
                    now
            ));
        }

        items.sort(Comparator.comparingInt(TodoItem::order));
        var state = new TodoListState(sessionId, safe(runId), List.copyOf(items), now);
        save(state);
        return state;
    }

    public TodoListState load(String sessionId) {
        var lock = sessionLocks.forKey(sessionId);
        lock.lock();
        try {
            return loadUnlocked(sessionId);
        } finally {
            lock.unlock();
        }
    }

    private TodoListState loadUnlocked(String sessionId) {
        Path file = todoFile(sessionId);
        if (!Files.exists(file)) {
            return new TodoListState(sessionId, "", List.of(), "");
        }
        try {
            JsonNode root = objectMapper.readTree(file.toFile());
            var items = new ArrayList<TodoItem>();
            JsonNode array = root.path("items");
            if (array.isArray()) {
                for (JsonNode node : array) {
                    items.add(new TodoItem(
                            node.path("id").asText(""),
                            node.path("content").asText(""),
                            TodoStatus.from(node.path("status").asText("pending")),
                            node.path("order").asInt(items.size() + 1),
                            node.path("created_at").asText(""),
                            node.path("updated_at").asText("")
                    ));
                }
            }
            return new TodoListState(
                    root.path("session_id").asText(sessionId),
                    root.path("run_id").asText(""),
                    List.copyOf(items),
                    root.path("updated_at").asText("")
            );
        } catch (Exception e) {
            return new TodoListState(sessionId, "", List.of(), "");
        }
    }

    public Map<String, Object> payload(TodoListState state) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("session_id", state.sessionId());
        payload.put("run_id", state.runId());
        payload.put("updated_at", state.updatedAt());
        payload.put("items", state.items().stream().map(item -> {
            var node = new LinkedHashMap<String, Object>();
            node.put("id", item.id());
            node.put("content", item.content());
            node.put("status", item.status().value());
            node.put("order", item.order());
            node.put("created_at", item.createdAt());
            node.put("updated_at", item.updatedAt());
            return node;
        }).toList());
        return payload;
    }

    private void save(TodoListState state) throws IOException {
        Path file = todoFile(state.sessionId());
        Files.createDirectories(file.getParent());
        objectMapper.writeValue(file.toFile(), payload(state));
    }

    private Path todoFile(String sessionId) {
        return fileSpaceManager.ensure(sessionId).root().resolve("todos").resolve("index.json");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value) {
        if (value.length() <= MAX_CONTENT_CHARS) {
            return value;
        }
        return value.substring(0, MAX_CONTENT_CHARS) + "...";
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
