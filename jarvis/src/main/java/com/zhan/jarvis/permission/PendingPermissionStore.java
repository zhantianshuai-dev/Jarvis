package com.zhan.jarvis.permission;

import com.zhan.jarvis.concurrency.StripedLock;
import com.zhan.jarvis.session.SessionFileSpaceManager;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 待确认权限存储。
 * 内存索引用于快速访问，JSON 文件用于刷新或服务重启后的恢复。
 */
@Component
public class PendingPermissionStore {

    private static final long CLEANUP_INTERVAL_MS = 60_000;

    private final ConcurrentHashMap<String, PendingToolPermission> permissions = new ConcurrentHashMap<>();
    private final StripedLock confirmLocks = new StripedLock(64);
    private final AtomicLong nextCleanupAtMs = new AtomicLong();
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
        return take(confirmId, null);
    }

    /**
     * 原子领取属于指定用户的待确认操作。
     * 用户不匹配时保留确认项，避免越权请求让合法用户失去确认机会。
     */
    public Optional<PendingToolPermission> take(String confirmId, String expectedUserId) {
        if (confirmId == null || confirmId.isBlank()) {
            return Optional.empty();
        }
        var lock = confirmLocks.forKey(confirmId);
        lock.lock();
        try {
            return takeLocked(confirmId, expectedUserId);
        } finally {
            lock.unlock();
        }
    }

    private Optional<PendingToolPermission> takeLocked(String confirmId, String expectedUserId) {
        cleanupExpired();
        PendingToolPermission permission = permissions.get(confirmId);
        if (permission == null) {
            permission = load(confirmId).orElse(null);
        }
        if (permission == null || permission.expiresAt().isBefore(Instant.now())) {
            permissions.remove(confirmId);
            deleteFile(confirmId);
            return Optional.empty();
        }
        if (hasText(expectedUserId) && hasText(permission.requestedBy())
                && !expectedUserId.equals(permission.requestedBy())) {
            throw new PermissionOwnerMismatchException("无权确认其他用户的工具操作");
        }
        permissions.remove(confirmId, permission);
        deleteFile(confirmId);
        return Optional.of(permission);
    }

    /** 撤销某个 Run 的全部待确认工具操作。 */
    public int revokeRun(String runId) {
        if (runId == null || runId.isBlank()) {
            return 0;
        }
        int[] removed = {0};
        permissions.entrySet().removeIf(entry -> {
            if (!belongsToRun(entry.getValue(), runId)) {
                return false;
            }
            deleteFile(entry.getKey());
            removed[0]++;
            return true;
        });
        if (Files.exists(fileSpaceManager.sessionsRoot())) {
            try (var stream = Files.find(fileSpaceManager.sessionsRoot(), 4,
                    (path, attrs) -> attrs.isRegularFile()
                            && path.getFileName().toString().startsWith("pending_")
                            && path.getFileName().toString().endsWith(".json"))) {
                stream.forEach(path -> {
                    try {
                        PendingToolPermission permission = objectMapper.readValue(
                                Files.readString(path), PendingToolPermission.class);
                        if (permission != null && belongsToRun(permission, runId)) {
                            permissions.remove(permission.confirmId());
                            if (Files.deleteIfExists(path)) {
                                removed[0]++;
                            }
                        }
                    } catch (Exception ignored) {
                        // 单个损坏文件不阻断其他待确认操作撤销。
                    }
                });
            } catch (Exception ignored) {
                // 磁盘清理失败不影响内存态撤销。
            }
        }
        return removed[0];
    }

    private static boolean belongsToRun(PendingToolPermission permission, String runId) {
        return permission.metadata() != null
                && runId.equals(String.valueOf(permission.metadata().getOrDefault("run_id", "")));
    }

    private void cleanupExpired() {
        long nowMs = System.currentTimeMillis();
        long scheduled = nextCleanupAtMs.get();
        if (nowMs < scheduled
                || !nextCleanupAtMs.compareAndSet(scheduled, nowMs + CLEANUP_INTERVAL_MS)) {
            return;
        }
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static final class PermissionOwnerMismatchException extends RuntimeException {
        public PermissionOwnerMismatchException(String message) {
            super(message);
        }
    }
}
