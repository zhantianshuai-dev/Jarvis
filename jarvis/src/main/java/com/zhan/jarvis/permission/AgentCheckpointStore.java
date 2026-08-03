package com.zhan.jarvis.permission;

import com.zhan.jarvis.session.SessionFileSpaceManager;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 检查点存储。
 * 内存索引用于当前运行态快速恢复，JSON 文件用于刷新或服务重启后的恢复。
 */
@Component
public class AgentCheckpointStore {

    private final ConcurrentHashMap<String, AgentCheckpoint> checkpoints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> confirmIndex = new ConcurrentHashMap<>();
    private final SessionFileSpaceManager fileSpaceManager;
    private final ObjectMapper objectMapper;

    public AgentCheckpointStore(SessionFileSpaceManager fileSpaceManager, ObjectMapper objectMapper) {
        this.fileSpaceManager = fileSpaceManager;
        this.objectMapper = objectMapper;
    }

    public void put(AgentCheckpoint checkpoint) {
        cleanupExpired();
        checkpoints.put(checkpoint.checkpointId(), checkpoint);
        if (checkpoint.confirmId() != null && !checkpoint.confirmId().isBlank()) {
            confirmIndex.put(checkpoint.confirmId(), checkpoint.checkpointId());
        }
        save(checkpoint);
    }

    public Optional<AgentCheckpoint> get(String checkpointId) {
        cleanupExpired();
        if (checkpointId == null || checkpointId.isBlank()) {
            return Optional.empty();
        }
        AgentCheckpoint checkpoint = checkpoints.get(checkpointId);
        if (checkpoint == null) {
            checkpoint = loadByCheckpointId(checkpointId).orElse(null);
        }
        if (checkpoint == null || checkpoint.expiresAt().isBefore(Instant.now())) {
            deleteByCheckpointId(checkpointId);
            return Optional.empty();
        }
        return Optional.of(checkpoint);
    }

    public Optional<AgentCheckpoint> take(String confirmId) {
        cleanupExpired();
        if (confirmId == null || confirmId.isBlank()) {
            return Optional.empty();
        }
        String checkpointId = confirmIndex.remove(confirmId);
        AgentCheckpoint checkpoint = null;
        if (checkpointId != null && !checkpointId.isBlank()) {
            checkpoint = checkpoints.remove(checkpointId);
        }
        if (checkpoint == null) {
            checkpoint = loadByConfirmId(confirmId).orElse(null);
            checkpointId = checkpoint != null ? checkpoint.checkpointId() : checkpointId;
        }
        if (checkpoint == null || checkpoint.expiresAt().isBefore(Instant.now())) {
            deleteByConfirmId(confirmId);
            return Optional.empty();
        }
        if (checkpointId != null) {
            checkpoints.remove(checkpointId);
        }
        deleteByConfirmId(confirmId);
        return Optional.of(checkpoint);
    }

    public List<AgentCheckpoint> listSession(String sessionId) {
        cleanupExpired();
        return checkpoints.values().stream()
                .filter(checkpoint -> sessionId == null || sessionId.isBlank()
                        || sessionId.equals(checkpoint.sessionId()))
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        checkpoints.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().expiresAt().isBefore(now);
            if (expired && entry.getValue().confirmId() != null) {
                confirmIndex.remove(entry.getValue().confirmId());
                deleteByConfirmId(entry.getValue().confirmId());
            }
            return expired;
        });
        confirmIndex.entrySet().removeIf(entry -> !checkpoints.containsKey(entry.getValue()));
        cleanupExpiredFiles(now);
    }

    private void save(AgentCheckpoint checkpoint) {
        try {
            Path file = checkpointFile(checkpoint);
            Files.writeString(file, objectMapper.writeValueAsString(checkpoint));
        } catch (Exception ignored) {
            // 文件持久化失败不影响当前内存态恢复。
        }
    }

    private Optional<AgentCheckpoint> loadByCheckpointId(String checkpointId) {
        Path file = findCheckpointFileByName("checkpoint_" + safe(checkpointId) + ".json");
        return readCheckpoint(file);
    }

    private Optional<AgentCheckpoint> loadByConfirmId(String confirmId) {
        Path file = findCheckpointFileByName("confirm_" + safe(confirmId) + ".json");
        return readCheckpoint(file);
    }

    private Optional<AgentCheckpoint> readCheckpoint(Path file) {
        if (file == null || !Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(Files.readString(file), AgentCheckpoint.class));
        } catch (Exception e) {
            deleteQuietly(file);
            return Optional.empty();
        }
    }

    private Path checkpointFile(AgentCheckpoint checkpoint) {
        String filename = checkpoint.confirmId() != null && !checkpoint.confirmId().isBlank()
                ? "confirm_" + safe(checkpoint.confirmId()) + ".json"
                : "checkpoint_" + safe(checkpoint.checkpointId()) + ".json";
        return fileSpaceManager.ensure(checkpoint.sessionId()).checkpoints().resolve(filename);
    }

    private Path findCheckpointFileByName(String filename) {
        try (var stream = Files.find(fileSpaceManager.sessionsRoot(), 4,
                (path, attrs) -> attrs.isRegularFile() && filename.equals(path.getFileName().toString()))) {
            return stream.findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteByCheckpointId(String checkpointId) {
        deleteQuietly(findCheckpointFileByName("checkpoint_" + safe(checkpointId) + ".json"));
    }

    private void deleteByConfirmId(String confirmId) {
        deleteQuietly(findCheckpointFileByName("confirm_" + safe(confirmId) + ".json"));
    }

    private void cleanupExpiredFiles(Instant now) {
        if (!Files.exists(fileSpaceManager.sessionsRoot())) {
            return;
        }
        try (var stream = Files.find(fileSpaceManager.sessionsRoot(), 4,
                (path, attrs) -> attrs.isRegularFile()
                        && (path.getFileName().toString().startsWith("confirm_")
                        || path.getFileName().toString().startsWith("checkpoint_"))
                        && path.getFileName().toString().endsWith(".json"))) {
            stream.forEach(path -> {
                try {
                    var checkpoint = objectMapper.readValue(Files.readString(path), AgentCheckpoint.class);
                    if (checkpoint == null || checkpoint.expiresAt().isBefore(now)) {
                        deleteQuietly(path);
                    }
                } catch (Exception e) {
                    deleteQuietly(path);
                }
            });
        } catch (Exception ignored) {
            // 清理失败不影响主流程。
        }
    }

    private void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
            // 删除失败不影响主流程。
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
