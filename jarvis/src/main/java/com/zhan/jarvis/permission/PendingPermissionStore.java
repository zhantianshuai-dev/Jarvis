package com.zhan.jarvis.permission;

import com.zhan.jarvis.session.SessionFileSpaceManager;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待确认权限存储。
 * 内存索引用于快速访问，JSON 文件用于刷新或服务重启后的恢复。
 */
@Component
public class PendingPermissionStore {

    private final ConcurrentHashMap<String, PendingToolPermission> permissions = new ConcurrentHashMap<>();
    private final SessionFileSpaceManager fileSpaceManager;
    private final ObjectMapper objectMapper;

    public PendingPermissionStore(SessionFileSpaceManager fileSpaceManager, ObjectMapper objectMapper) {
        this.fileSpaceManager = fileSpaceManager;
        this.objectMapper = objectMapper;
    }

    public void put(PendingToolPermission permission) {
        cleanupExpired();
        // 存储形式：key 为确认 ID，value 为待确认权限。
        permissions.put(permission.confirmId(), permission);
        save(permission);
    }

    public Optional<PendingToolPermission> take(String confirmId) {
        cleanupExpired();
        if (confirmId == null || confirmId.isBlank()) {
            return Optional.empty();
        }
        PendingToolPermission permission = permissions.remove(confirmId);
        if (permission == null) {
            permission = load(confirmId).orElse(null);
        }
        if (permission == null || permission.expiresAt().isBefore(Instant.now())) {
            deleteFile(confirmId);
            return Optional.empty();
        }
        deleteFile(confirmId);
        return Optional.of(permission);
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        permissions.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().expiresAt().isBefore(now);
            if (expired) {
                deleteFile(entry.getKey());
            }
            return expired;
        });
        cleanupExpiredFiles(now);
    }

    private void save(PendingToolPermission permission) {
        try {
            Path file = pendingFile(permission);
            Files.writeString(file, objectMapper.writeValueAsString(permission));
        } catch (Exception ignored) {
            // 文件持久化失败不影响内存态确认流程。
        }
    }

    private Optional<PendingToolPermission> load(String confirmId) {
        Path file = findPendingFile(confirmId);
        if (file == null || !Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(Files.readString(file), PendingToolPermission.class));
        } catch (Exception e) {
            deleteQuietly(file);
            return Optional.empty();
        }
    }

    private Path pendingFile(PendingToolPermission permission) {
        String sessionId = permission.sessionId() != null && !permission.sessionId().isBlank()
                ? permission.sessionId()
                : "tool-confirm";
        return fileSpaceManager.ensure(sessionId).checkpoints()
                .resolve("pending_" + safeConfirmId(permission.confirmId()) + ".json");
    }

    private Path findPendingFile(String confirmId) {
        String filename = "pending_" + safeConfirmId(confirmId) + ".json";
        try (var stream = Files.find(fileSpaceManager.sessionsRoot(), 4,
                (path, attrs) -> attrs.isRegularFile() && filename.equals(path.getFileName().toString()))) {
            return stream.findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteFile(String confirmId) {
        deleteQuietly(findPendingFile(confirmId));
    }

    private void cleanupExpiredFiles(Instant now) {
        if (!Files.exists(fileSpaceManager.sessionsRoot())) {
            return;
        }
        try (var stream = Files.find(fileSpaceManager.sessionsRoot(), 4,
                (path, attrs) -> attrs.isRegularFile()
                        && path.getFileName().toString().startsWith("pending_")
                        && path.getFileName().toString().endsWith(".json"))) {
            stream.forEach(path -> {
                try {
                    var permission = objectMapper.readValue(Files.readString(path), PendingToolPermission.class);
                    if (permission == null || permission.expiresAt().isBefore(now)) {
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
            // 删除失败不影响确认主流程。
        }
    }

    private String safeConfirmId(String confirmId) {
        return confirmId == null ? "" : confirmId.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
