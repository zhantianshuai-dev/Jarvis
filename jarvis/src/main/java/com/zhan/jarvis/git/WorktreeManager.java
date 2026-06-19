package com.zhan.jarvis.git;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 管理 Jarvis workspace 下的 Git worktree。
 * 状态写入 .worktrees/index.json，生命周期事件写入 .worktrees/events.jsonl。
 */
public class WorktreeManager {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private static final int TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_CHARS = 50_000;

    private final ObjectMapper objectMapper;
    private final Path workspace;
    private final Path worktreesDir;
    private final Path indexFile;
    private final Path eventsFile;

    public WorktreeManager(ObjectMapper objectMapper, String workspaceDir) {
        this.objectMapper = objectMapper;
        this.workspace = Path.of(workspaceDir).toAbsolutePath().normalize();
        this.worktreesDir = workspace.resolve(".worktrees").normalize();
        this.indexFile = worktreesDir.resolve("index.json");
        this.eventsFile = worktreesDir.resolve("events.jsonl");
    }

    public synchronized List<WorktreeEntry> list() {
        return List.copyOf(loadIndex());
    }

    public synchronized Optional<WorktreeEntry> get(String name) throws IOException {
        validateName(name);
        return loadIndex().stream()
                .filter(entry -> entry.name().equals(name))
                .findFirst();
    }

    public synchronized WorktreeResult create(String name, String baseRef, String taskId) {
        try {
            validateName(name);
            String branch = "wt/" + name;
            String safeBaseRef = safeRef(baseRef == null || baseRef.isBlank() ? "HEAD" : baseRef);
            Path path = worktreePath(name);
            if (Files.exists(path)) {
                return WorktreeResult.error("worktree 路径已存在: " + path);
            }
            List<WorktreeEntry> entries = new ArrayList<>(loadIndex());
            if (entries.stream().anyMatch(e -> e.name().equals(name) && "active".equals(e.status()))) {
                return WorktreeResult.error("worktree 已存在: " + name);
            }

            Files.createDirectories(worktreesDir);
            appendEvent("worktree.create.before", Map.of("name", name, "branch", branch, "base_ref", safeBaseRef));
            //这里执行git worktree add 脚本
            GitProcessResult result = runGit(List.of(
                    "git", "worktree", "add", "-b", branch, workspace.relativize(path).toString(), safeBaseRef
            ), workspace);
            if (result.exitCode() != 0 || result.timedOut()) {
                appendEvent("worktree.create.failed", Map.of("name", name, "output", result.output()));
                return WorktreeResult.commandError("create", result);
            }

            String now = Instant.now().toString();
            entries.removeIf(e -> e.name().equals(name));
            entries.add(new WorktreeEntry(name, workspace.relativize(path).toString(), branch, safeBaseRef,
                    taskId == null ? "" : taskId, "active", false, now, now));
            saveIndex(entries);
            appendEvent("worktree.create.after", Map.of("name", name, "path", path.toString(), "branch", branch));
            return WorktreeResult.success("create", result, entryPayload(entries.get(entries.size() - 1)));
        } catch (Exception e) {
            return WorktreeResult.error(e.getMessage());
        }
    }

    public synchronized WorktreeResult remove(String name, boolean force) {
        try {
            validateName(name);
            List<WorktreeEntry> entries = new ArrayList<>(loadIndex());
            WorktreeEntry entry = findActive(entries, name);
            if (entry == null) {
                return WorktreeResult.error("worktree 不存在或已非 active: " + name);
            }

            Path path = resolveStoredPath(entry.path());
            var command = new ArrayList<String>();
            command.add("git");
            command.add("worktree");
            command.add("remove");
            if (force) {
                command.add("--force");
            }
            command.add(workspace.relativize(path).toString());

            appendEvent("worktree.remove.before", Map.of("name", name, "force", force));
            GitProcessResult result = runGit(command, workspace);
            if (result.exitCode() != 0 || result.timedOut()) {
                appendEvent("worktree.remove.failed", Map.of("name", name, "output", result.output()));
                return WorktreeResult.commandError("remove", result);
            }

            String now = Instant.now().toString();
            entries.replaceAll(e -> e.name().equals(name) ? e.withStatus("removed", now) : e);
            saveIndex(entries);
            appendEvent("worktree.remove.after", Map.of("name", name));
            return WorktreeResult.success("remove", result, Map.of("name", name, "status", "removed"));
        } catch (Exception e) {
            return WorktreeResult.error(e.getMessage());
        }
    }

    public synchronized WorktreeResult keep(String name) {
        try {
            validateName(name);
            List<WorktreeEntry> entries = new ArrayList<>(loadIndex());
            WorktreeEntry entry = findActive(entries, name);
            if (entry == null) {
                return WorktreeResult.error("worktree 不存在或已非 active: " + name);
            }
            String now = Instant.now().toString();
            entries.replaceAll(e -> e.name().equals(name) ? e.withKept(true, now) : e);
            saveIndex(entries);
            appendEvent("worktree.keep", Map.of("name", name));
            WorktreeEntry updated = findActive(entries, name);
            return WorktreeResult.success("keep", new GitProcessResult(0, "", false),
                    entryPayload(updated == null ? entry : updated));
        } catch (Exception e) {
            return WorktreeResult.error(e.getMessage());
        }
    }

    public synchronized WorktreeResult bindTask(String name, String taskId) {
        try {
            validateName(name);
            List<WorktreeEntry> entries = new ArrayList<>(loadIndex());
            WorktreeEntry entry = findActive(entries, name);
            if (entry == null) {
                return WorktreeResult.error("worktree 不存在或已非 active: " + name);
            }
            String now = Instant.now().toString();
            entries.replaceAll(e -> e.name().equals(name) ? e.withTaskId(taskId == null ? "" : taskId, now) : e);
            saveIndex(entries);
            appendEvent("worktree.task_bound", Map.of("name", name, "task_id", taskId == null ? "" : taskId));
            WorktreeEntry updated = findActive(entries, name);
            return WorktreeResult.success("bind_task", new GitProcessResult(0, "", false),
                    entryPayload(updated == null ? entry : updated));
        } catch (Exception e) {
            return WorktreeResult.error(e.getMessage());
        }
    }

    public synchronized Path path(String name) throws IOException {
        validateName(name);
        WorktreeEntry entry = findActive(loadIndex(), name);
        if (entry == null) {
            throw new IOException("worktree 不存在或已非 active: " + name);
        }
        return resolveStoredPath(entry.path());
    }

    public Map<String, Object> entryPayload(WorktreeEntry entry) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("name", entry.name());
        payload.put("path", entry.path());
        payload.put("absolute_path", resolveStoredPath(entry.path()).toString());
        payload.put("branch", entry.branch());
        payload.put("base_ref", entry.baseRef());
        payload.put("task_id", entry.taskId());
        payload.put("status", entry.status());
        payload.put("kept", entry.kept());
        payload.put("created_at", entry.createdAt());
        payload.put("updated_at", entry.updatedAt());
        return payload;
    }

    private List<WorktreeEntry> loadIndex() {
        if (!Files.exists(indexFile)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(indexFile.toFile());
            var result = new ArrayList<WorktreeEntry>();
            if (root.has("worktrees") && root.get("worktrees").isArray()) {
                for (JsonNode node : root.get("worktrees")) {
                    result.add(new WorktreeEntry(
                            text(node, "name", ""),
                            text(node, "path", ""),
                            text(node, "branch", ""),
                            text(node, "base_ref", ""),
                            text(node, "task_id", ""),
                            text(node, "status", "active"),
                            node.has("kept") && node.get("kept").asBoolean(),
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

    private void saveIndex(List<WorktreeEntry> entries) throws IOException {
        Files.createDirectories(worktreesDir);
        var root = objectMapper.createObjectNode();
        root.put("version", 1);
        root.put("updated_at", Instant.now().toString());
        var array = root.putArray("worktrees");
        for (WorktreeEntry entry : entries) {
            var node = array.addObject();
            node.put("name", entry.name());
            node.put("path", entry.path());
            node.put("branch", entry.branch());
            node.put("base_ref", entry.baseRef());
            node.put("task_id", entry.taskId());
            node.put("status", entry.status());
            node.put("kept", entry.kept());
            node.put("created_at", entry.createdAt());
            node.put("updated_at", entry.updatedAt());
        }
        Files.writeString(indexFile, objectMapper.writeValueAsString(root));
    }

    private void appendEvent(String type, Map<String, Object> data) {
        try {
            Files.createDirectories(worktreesDir);
            var event = new LinkedHashMap<String, Object>();
            event.put("ts", Instant.now().toString());
            event.put("type", type);
            event.put("data", data);
            Files.writeString(eventsFile, objectMapper.writeValueAsString(event) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 审计日志失败不影响主流程，工具结果仍以 Git 命令为准。
        }
    }

    private WorktreeEntry findActive(List<WorktreeEntry> entries, String name) {
        return entries.stream()
                .filter(e -> e.name().equals(name) && "active".equals(e.status()))
                .findFirst()
                .orElse(null);
    }

    private Path worktreePath(String name) throws IOException {
        Path path = worktreesDir.resolve(name).normalize();
        if (!path.startsWith(worktreesDir)) {
            throw new IOException("worktree 路径越界: " + name);
        }
        return path;
    }

    private Path resolveStoredPath(String path) {
        Path candidate = Path.of(path);
        return candidate.isAbsolute()
                ? candidate.toAbsolutePath().normalize()
                : workspace.resolve(candidate).normalize();
    }

    private void validateName(String name) throws IOException {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IOException("worktree name 只能包含字母、数字、点、下划线和中划线，长度 1-80");
        }
        if (".".equals(name) || "..".equals(name)) {
            throw new IOException("worktree name 不安全: " + name);
        }
    }

    private String safeRef(String ref) throws IOException {
        String value = ref.strip();
        if (value.isBlank() || value.startsWith("-")
                || value.contains("\u0000") || value.contains("\n") || value.contains("\r")
                || value.contains("..") || value.contains("@{") || value.contains("\\")
                || value.contains("//")) {
            throw new IOException("base_ref 不安全: " + ref);
        }
        return value;
    }

    private GitProcessResult runGit(List<String> command, Path cwd) {
        var output = new StringBuilder();
        try {
            var pb = new ProcessBuilder(command);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            var process = pb.start();
            Thread reader = Thread.startVirtualThread(() -> readOutput(process, output));
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(TimeUnit.SECONDS.toMillis(1));
                return new GitProcessResult(-1, output.toString(), true);
            }
            reader.join(TimeUnit.SECONDS.toMillis(1));
            return new GitProcessResult(process.exitValue(), output.toString(), false);
        } catch (Exception e) {
            return new GitProcessResult(-1, "Git worktree 命令执行异常: " + e.getMessage(), false);
        }
    }

    private static void readOutput(Process process, StringBuilder output) {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    if (output.length() < MAX_OUTPUT_CHARS) {
                        output.append(line).append('\n');
                    }
                }
            }
        } catch (IOException ignored) {
            // 进程结束或超时时流可能关闭，返回已读取内容即可。
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : fallback;
    }

    public record WorktreeEntry(
            String name,
            String path,
            String branch,
            String baseRef,
            String taskId,
            String status,
            boolean kept,
            String createdAt,
            String updatedAt
    ) {
        WorktreeEntry withStatus(String status, String updatedAt) {
            return new WorktreeEntry(name, path, branch, baseRef, taskId, status, kept, createdAt, updatedAt);
        }

        WorktreeEntry withKept(boolean kept, String updatedAt) {
            return new WorktreeEntry(name, path, branch, baseRef, taskId, status, kept, createdAt, updatedAt);
        }

        WorktreeEntry withTaskId(String taskId, String updatedAt) {
            return new WorktreeEntry(name, path, branch, baseRef, taskId, status, kept, createdAt, updatedAt);
        }
    }

    public record GitProcessResult(int exitCode, String output, boolean timedOut) {}

    public record WorktreeResult(boolean success, String action, String error, GitProcessResult command,
                                 Map<String, Object> data) {
        static WorktreeResult success(String action, GitProcessResult command, Map<String, Object> data) {
            return new WorktreeResult(true, action, "", command, data);
        }

        static WorktreeResult error(String error) {
            return new WorktreeResult(false, "", error, new GitProcessResult(-1, "", false), Map.of());
        }

        static WorktreeResult commandError(String action, GitProcessResult command) {
            return new WorktreeResult(false, action, "Git worktree 命令执行失败", command, Map.of());
        }
    }
}
