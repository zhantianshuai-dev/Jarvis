package com.zhan.jarvis.task;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 本地任务状态管理。
 * 第一版只服务 subagent 任务，状态写入 workspace/.tasks。
 */
public class TaskManager {

    private static final int MAX_TEXT_CHARS = 20_000;

    private final ObjectMapper objectMapper;
    private final Path tasksDir;
    private final Path indexFile;
    private final Path eventsFile;

    public TaskManager(ObjectMapper objectMapper, String workspaceDir) {
        this.objectMapper = objectMapper;
        this.tasksDir = Path.of(workspaceDir).toAbsolutePath().normalize().resolve(".tasks");
        this.indexFile = tasksDir.resolve("index.json");
        this.eventsFile = tasksDir.resolve("events.jsonl");
    }

    public synchronized TaskRecord createSubagentTask(String taskId, String ownerUserId, String sessionId,
                                                       String sessionKey, String description) {
        String now = Instant.now().toString();
        TaskRecord record = new TaskRecord(
                safe(taskId),
                "subagent",
                "pending",
                safe(ownerUserId),
                safe(sessionId),
                safe(sessionKey),
                truncate(description),
                "",
                "",
                "",
                "",
                now,
                now
        );
        var tasks = new ArrayList<>(loadIndex());
        tasks.removeIf(task -> task.taskId().equals(record.taskId()));
        tasks.add(record);
        saveIndex(tasks);
        appendEvent("task.created", Map.of("task_id", record.taskId(), "type", record.type()));
        return record;
    }

    public synchronized TaskRecord bindWorktree(String taskId, String worktree, String worktreePath) throws IOException {
        String now = Instant.now().toString();
        TaskRecord updated = update(taskId, record -> record.withWorktree(
                safe(worktree),
                safe(worktreePath),
                now
        ));
        appendEvent("task.worktree_bound", Map.of(
                "task_id", updated.taskId(),
                "worktree", updated.worktree(),
                "worktree_path", updated.worktreePath()
        ));
        return updated;
    }

    public synchronized TaskRecord markRunning(String taskId) throws IOException {
        TaskRecord updated = updateStatus(taskId, "running", "", "");
        appendEvent("task.running", Map.of("task_id", updated.taskId()));
        return updated;
    }

    public synchronized TaskRecord complete(String taskId, String result) throws IOException {
        TaskRecord updated = updateStatus(taskId, "completed", truncate(result), "");
        appendEvent("task.completed", Map.of("task_id", updated.taskId()));
        return updated;
    }

    public synchronized TaskRecord fail(String taskId, String error) throws IOException {
        TaskRecord updated = updateStatus(taskId, "failed", "", truncate(error));
        appendEvent("task.failed", Map.of("task_id", updated.taskId(), "error", updated.error()));
        return updated;
    }

    public synchronized List<TaskRecord> list() {
        return List.copyOf(loadIndex());
    }

    public synchronized Optional<TaskRecord> get(String taskId) {
        return loadIndex().stream()
                .filter(task -> task.taskId().equals(taskId))
                .findFirst();
    }

    public Map<String, Object> payload(TaskRecord task) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("task_id", task.taskId());
        payload.put("type", task.type());
        payload.put("status", task.status());
        payload.put("owner_user_id", task.ownerUserId());
        payload.put("session_id", task.sessionId());
        payload.put("session_key", task.sessionKey());
        payload.put("description", task.description());
        payload.put("worktree", task.worktree());
        payload.put("worktree_path", task.worktreePath());
        payload.put("result", task.result());
        payload.put("error", task.error());
        payload.put("created_at", task.createdAt());
        payload.put("updated_at", task.updatedAt());
        return payload;
    }

    private TaskRecord updateStatus(String taskId, String status, String result, String error) throws IOException {
        String now = Instant.now().toString();
        return update(taskId, record -> record.withStatus(status, safe(result), safe(error), now));
    }

    private TaskRecord update(String taskId, TaskUpdater updater) throws IOException {
        var tasks = new ArrayList<>(loadIndex());
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).taskId().equals(taskId)) {
                TaskRecord updated = updater.update(tasks.get(i));
                tasks.set(i, updated);
                saveIndex(tasks);
                return updated;
            }
        }
        throw new IOException("任务不存在: " + taskId);
    }

    private List<TaskRecord> loadIndex() {
        if (!Files.exists(indexFile)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(indexFile.toFile());
            var result = new ArrayList<TaskRecord>();
            if (root.has("tasks") && root.get("tasks").isArray()) {
                for (JsonNode node : root.get("tasks")) {
                    result.add(new TaskRecord(
                            text(node, "task_id", ""),
                            text(node, "type", "subagent"),
                            text(node, "status", "pending"),
                            text(node, "owner_user_id", ""),
                            text(node, "session_id", ""),
                            text(node, "session_key", ""),
                            text(node, "description", ""),
                            text(node, "worktree", ""),
                            text(node, "worktree_path", ""),
                            text(node, "result", ""),
                            text(node, "error", ""),
                            text(node, "created_at", ""),
                            text(node, "updated_at", "")
                    ));
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void saveIndex(List<TaskRecord> tasks) {
        try {
            Files.createDirectories(tasksDir);
            var root = objectMapper.createObjectNode();
            root.put("version", 1);
            root.put("updated_at", Instant.now().toString());
            var array = root.putArray("tasks");
            for (TaskRecord task : tasks) {
                var node = array.addObject();
                node.put("task_id", task.taskId());
                node.put("type", task.type());
                node.put("status", task.status());
                node.put("owner_user_id", task.ownerUserId());
                node.put("session_id", task.sessionId());
                node.put("session_key", task.sessionKey());
                node.put("description", task.description());
                node.put("worktree", task.worktree());
                node.put("worktree_path", task.worktreePath());
                node.put("result", task.result());
                node.put("error", task.error());
                node.put("created_at", task.createdAt());
                node.put("updated_at", task.updatedAt());
            }
            Files.writeString(indexFile, objectMapper.writeValueAsString(root));
        } catch (IOException e) {
            throw new IllegalStateException("保存任务索引失败: " + e.getMessage(), e);
        }
    }

    private void appendEvent(String type, Map<String, Object> data) {
        try {
            Files.createDirectories(tasksDir);
            var event = new LinkedHashMap<String, Object>();
            event.put("ts", Instant.now().toString());
            event.put("type", type);
            event.put("data", data);
            Files.writeString(eventsFile, objectMapper.writeValueAsString(event) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 任务事件只是审计信息，失败不影响主流程。
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : fallback;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value) {
        String safe = safe(value);
        if (safe.length() <= MAX_TEXT_CHARS) {
            return safe;
        }
        return safe.substring(0, MAX_TEXT_CHARS) + "\n...[truncated]";
    }

    private interface TaskUpdater {
        TaskRecord update(TaskRecord record);
    }
}
