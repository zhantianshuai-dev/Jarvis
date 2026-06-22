package com.zhan.jarvis.permission;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 pending permission 存储。
 * 第一版服务重启后全部失效，符合“确认只对当前运行态有效”的安全原则。
 */
@Component
public class PendingPermissionStore {

    private final ConcurrentHashMap<String, PendingToolPermission> permissions = new ConcurrentHashMap<>();

    public void put(PendingToolPermission permission) {
        cleanupExpired();
        //存储形式 key： confirmId ，value： permission
        permissions.put(permission.confirmId(), permission);
    }

    public Optional<PendingToolPermission> take(String confirmId) {
        cleanupExpired();
        if (confirmId == null || confirmId.isBlank()) {
            return Optional.empty();
        }
        PendingToolPermission permission = permissions.remove(confirmId);
        if (permission == null || permission.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(permission);
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        permissions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }
}
