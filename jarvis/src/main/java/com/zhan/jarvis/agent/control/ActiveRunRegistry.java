package com.zhan.jarvis.agent.control;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 活动 Run 控制面，同时维护可跨页面刷新的状态快照。
 */
public class ActiveRunRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActiveRunRegistry.class);
    private static final int MAX_PERSISTED_RUNS = 2_000;

    private final ConcurrentHashMap<String, RunControl> controls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RunSnapshot> snapshots = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Path indexFile;

    public ActiveRunRegistry(Path workspace, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.indexFile = workspace == null || objectMapper == null
                ? null
                : workspace.toAbsolutePath().normalize().resolve("runs").resolve("index.json");
        loadAndReconcile();
    }

    public static ActiveRunRegistry inMemory() {
        return new ActiveRunRegistry(null, null);
    }

    public RunSnapshot prepare(String runId, String sessionId, String ownerUserId) {
        String id = requireText(runId, "runId");
        Instant now = Instant.now();
        RunSnapshot snapshot = snapshots.compute(id, (ignored, existing) -> {
            if (existing != null && !existing.status().terminal()) {
                return existing;
            }
            return new RunSnapshot(id, value(sessionId), value(ownerUserId), RunStatus.QUEUED,
                    "", now, null, null, now);
        });
        controls.computeIfAbsent(id, ignored -> new RunControl(snapshot));
        persist();
        return snapshot;
    }

    public TurnCancellationToken start(String runId, String sessionId, String ownerUserId, Thread ownerThread) {
        prepare(runId, sessionId, ownerUserId);
        RunControl control = controls.computeIfAbsent(runId,
                ignored -> new RunControl(snapshots.get(runId)));
        control.ownerThread.set(ownerThread);
        if (!control.source.token().isCancellationRequested()) {
            update(control, RunStatus.RUNNING, "", false);
        } else if (ownerThread != null) {
            ownerThread.interrupt();
        }
        return control.source.token();
    }

    public Optional<RunSnapshot> get(String runId) {
        return Optional.ofNullable(snapshots.get(runId));
    }

    public List<RunSnapshot> listSession(String sessionId, String ownerUserId) {
        return snapshots.values().stream()
                .filter(snapshot -> value(sessionId).isBlank() || value(sessionId).equals(snapshot.sessionId()))
                .filter(snapshot -> value(ownerUserId).isBlank() || value(ownerUserId).equals(snapshot.ownerUserId()))
                .sorted(Comparator.comparing(RunSnapshot::updatedAt).reversed())
                .toList();
    }

    public RunSnapshot interrupt(String runId, String requesterUserId, String reason) {
        RunSnapshot snapshot = snapshots.get(runId);
        if (snapshot == null) {
            throw new RunNotFoundException(runId);
        }
        if (!snapshot.ownerUserId().isBlank() && !snapshot.ownerUserId().equals(value(requesterUserId))) {
            throw new RunAccessDeniedException(runId);
        }
        if (snapshot.status().terminal()) {
            return snapshot;
        }
        RunControl control = controls.computeIfAbsent(runId, ignored -> new RunControl(snapshot));
        String normalizedReason = value(reason).isBlank() ? "user_interrupted" : reason;
        update(control, RunStatus.CANCELLING, normalizedReason, false);
        control.source.cancel(normalizedReason);
        Thread owner = control.ownerThread.get();
        if (owner != null && owner != Thread.currentThread()) {
            owner.interrupt();
        }
        return control.snapshot.get();
    }

    public RunSnapshot markWaitingConfirmation(String runId) {
        return transition(runId, RunStatus.WAITING_CONFIRMATION, "", false);
    }

    public RunSnapshot complete(String runId) {
        return transition(runId, RunStatus.COMPLETED, "", true);
    }

    public RunSnapshot fail(String runId, String reason) {
        return transition(runId, RunStatus.FAILED, reason, true);
    }

    public RunSnapshot interrupted(String runId, String reason) {
        return transition(runId, RunStatus.INTERRUPTED, reason, true);
    }

    public TurnCancellationToken token(String runId) {
        RunControl control = controls.get(runId);
        return control == null ? TurnCancellationToken.none() : control.source.token();
    }

    private RunSnapshot transition(String runId, RunStatus status, String reason, boolean terminal) {
        RunSnapshot existing = snapshots.get(runId);
        if (existing == null) {
            throw new RunNotFoundException(runId);
        }
        if (existing.status().terminal()) {
            return existing;
        }
        RunControl control = controls.computeIfAbsent(runId, ignored -> new RunControl(existing));
        RunSnapshot updated = update(control, status, reason, terminal);
        if (terminal) {
            controls.remove(runId, control);
            control.source.close();
        }
        return updated;
    }

    private RunSnapshot update(RunControl control, RunStatus status, String reason, boolean terminal) {
        Instant now = Instant.now();
        RunSnapshot updated = control.snapshot.updateAndGet(previous -> {
            if (previous.status().terminal()) {
                return previous;
            }
            Instant startedAt = previous.startedAt();
            if (status == RunStatus.RUNNING && startedAt == null) {
                startedAt = now;
            }
            return new RunSnapshot(previous.runId(), previous.sessionId(), previous.ownerUserId(), status,
                    value(reason), previous.createdAt(), startedAt, terminal ? now : null, now);
        });
        snapshots.put(updated.runId(), updated);
        persist();
        return updated;
    }

    private synchronized void persist() {
        if (indexFile == null) {
            return;
        }
        try {
            Files.createDirectories(indexFile.getParent());
            var root = objectMapper.createObjectNode();
            root.put("version", 1);
            root.put("updated_at", Instant.now().toString());
            var runs = root.putArray("runs");
            snapshots.values().stream()
                    .sorted(Comparator.comparing(RunSnapshot::updatedAt).reversed())
                    .limit(MAX_PERSISTED_RUNS)
                    .forEach(snapshot -> {
                        var node = runs.addObject();
                        node.put("run_id", snapshot.runId());
                        node.put("session_id", snapshot.sessionId());
                        node.put("owner_user_id", snapshot.ownerUserId());
                        node.put("status", snapshot.status().value());
                        node.put("reason", value(snapshot.reason()));
                        node.put("created_at", instantText(snapshot.createdAt()));
                        node.put("started_at", instantText(snapshot.startedAt()));
                        node.put("completed_at", instantText(snapshot.completedAt()));
                        node.put("updated_at", instantText(snapshot.updatedAt()));
                    });
            Path temp = indexFile.resolveSibling(indexFile.getFileName() + ".tmp");
            Files.writeString(temp, objectMapper.writeValueAsString(root));
            Files.move(temp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.warn("持久化 Run 状态失败: file={}, error={}", indexFile, e.getMessage());
        }
    }

    private void loadAndReconcile() {
        if (indexFile == null || !Files.exists(indexFile)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(indexFile.toFile());
            Instant now = Instant.now();
            for (JsonNode node : root.path("runs")) {
                RunStatus status = parseStatus(node.path("status").asText("failed"));
                String reason = node.path("reason").asText("");
                if (status == RunStatus.QUEUED || status == RunStatus.RUNNING || status == RunStatus.CANCELLING) {
                    status = RunStatus.INTERRUPTED;
                    reason = "server_restarted";
                }
                RunSnapshot snapshot = new RunSnapshot(
                        node.path("run_id").asText(""),
                        node.path("session_id").asText(""),
                        node.path("owner_user_id").asText(""),
                        status,
                        reason,
                        parseInstant(node.path("created_at").asText(""), now),
                        parseInstant(node.path("started_at").asText(""), null),
                        status.terminal() ? parseInstant(node.path("completed_at").asText(""), now) : null,
                        parseInstant(node.path("updated_at").asText(""), now)
                );
                if (!snapshot.runId().isBlank()) {
                    snapshots.put(snapshot.runId(), snapshot);
                    if (!snapshot.status().terminal()) {
                        controls.put(snapshot.runId(), new RunControl(snapshot));
                    }
                }
            }
            persist();
        } catch (Exception e) {
            log.warn("加载 Run 状态失败: file={}, error={}", indexFile, e.getMessage());
        }
    }

    private static RunStatus parseStatus(String value) {
        try {
            return RunStatus.valueOf(value.toUpperCase());
        } catch (Exception e) {
            return RunStatus.FAILED;
        }
    }

    private static Instant parseInstant(String value, Instant fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Instant.parse(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String instantText(Instant value) {
        return value == null ? "" : value.toString();
    }

    private static final class RunControl {
        private final TurnCancellationSource source = new TurnCancellationSource();
        private final AtomicReference<Thread> ownerThread = new AtomicReference<>();
        private final AtomicReference<RunSnapshot> snapshot;

        private RunControl(RunSnapshot snapshot) {
            this.snapshot = new AtomicReference<>(snapshot);
        }
    }

    public static class RunNotFoundException extends RuntimeException {
        public RunNotFoundException(String runId) {
            super("Run 不存在: " + runId);
        }
    }

    public static class RunAccessDeniedException extends RuntimeException {
        public RunAccessDeniedException(String runId) {
            super("无权终止 Run: " + runId);
        }
    }
}
