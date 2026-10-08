package com.zhan.jarvis.agent.control;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可持久化、可返回给前端的 Run 状态快照。
 */
public record RunSnapshot(
        String runId,
        String sessionId,
        String ownerUserId,
        RunStatus status,
        String reason,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt
) {
    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("run_id", runId);
        result.put("session_id", sessionId);
        result.put("owner_user_id", ownerUserId);
        result.put("status", status.value());
        result.put("reason", reason == null ? "" : reason);
        result.put("created_at", text(createdAt));
        result.put("started_at", text(startedAt));
        result.put("completed_at", text(completedAt));
        result.put("updated_at", text(updatedAt));
        return result;
    }

    private static String text(Instant instant) {
        return instant == null ? "" : instant.toString();
    }
}
