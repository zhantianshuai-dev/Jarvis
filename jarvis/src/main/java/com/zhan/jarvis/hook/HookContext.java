package com.zhan.jarvis.hook;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hook 执行上下文。
 *
 * @param eventType 生命周期事件名，如 agent.pre_process / tool.post_call
 * @param sessionId 当前会话 ID
 * @param userId 当前用户 ID
 * @param payload 事件附加数据
 * @param createdAt 事件创建时间
 */
public record HookContext(
        String eventType,
        String sessionId,
        String userId,
        Map<String, Object> payload,
        Instant createdAt
) {
    public static HookContext of(String eventType, String sessionId, String userId,
                                 Map<String, Object> payload) {
        return new HookContext(
                eventType,
                sessionId,
                userId,
                payload == null ? Map.of() : new LinkedHashMap<>(payload),
                Instant.now()
        );
    }
}
