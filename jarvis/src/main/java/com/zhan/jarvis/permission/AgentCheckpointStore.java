package com.zhan.jarvis.permission;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 Agent 检查点存储。
 * 服务重启后中断点失效；这符合第一版“只确认当前运行态操作”的安全边界。
 */
@Component
public class AgentCheckpointStore {

    private final ConcurrentHashMap<String, AgentCheckpoint> checkpoints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> confirmIndex = new ConcurrentHashMap<>();

    public void put(AgentCheckpoint checkpoint) {
        cleanupExpired();
        checkpoints.put(checkpoint.checkpointId(), checkpoint);
        if (checkpoint.confirmId() != null && !checkpoint.confirmId().isBlank()) {
            confirmIndex.put(checkpoint.confirmId(), checkpoint.checkpointId());
        }
    }

    public Optional<AgentCheckpoint> get(String checkpointId) {
        cleanupExpired();
        if (checkpointId == null || checkpointId.isBlank()) {
            return Optional.empty();
        }
        AgentCheckpoint checkpoint = checkpoints.get(checkpointId);
        if (checkpoint == null || checkpoint.expiresAt().isBefore(Instant.now())) {
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
        if (checkpointId == null || checkpointId.isBlank()) {
            return Optional.empty();
        }
        AgentCheckpoint checkpoint = checkpoints.remove(checkpointId);
        if (checkpoint == null || checkpoint.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
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
            }
            return expired;
        });
        confirmIndex.entrySet().removeIf(entry -> !checkpoints.containsKey(entry.getValue()));
    }
}
