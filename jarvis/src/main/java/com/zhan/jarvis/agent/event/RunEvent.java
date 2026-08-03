package com.zhan.jarvis.agent.event;

import java.time.Instant;
import java.util.Map;

/**
 * Agent 单次运行事件。
 * 用于运行轨迹、回放、调试和后续 checkpoint 恢复。
 */
public record RunEvent(
        String eventId,
        String runId,
        String sessionId,
        String userId,
        String type,
        String content,
        Map<String, Object> metadata,
        Instant createdAt
) {
}
