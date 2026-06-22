package com.zhan.jarvis.permission;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 Agent checkpoint 存储。
 * 服务重启后中断点失效；这符合第一版“只确认当前运行态操作”的安全边界。
 */
@Component
public class AgentCheckpointStore {

    private final ConcurrentHashMap<String, AgentCheckpoint> checkpoints = new ConcurrentHashMap<>();

    public void put(AgentCheckpoint checkpoint) {
        cleanupExpired();
        checkpoints.put(checkpoint.confirmId(), checkpoint);
    }

    public Optional<AgentCheckpoint> take(String confirmId) {
        cleanupExpired();
        if (confirmId == null || confirmId.isBlank()) {
            return Optional.empty();
        }
        AgentCheckpoint checkpoint = checkpoints.remove(confirmId);
        if (checkpoint == null || checkpoint.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(checkpoint);
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        checkpoints.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }
}
