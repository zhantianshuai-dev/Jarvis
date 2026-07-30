package com.zhan.jarvis.permission;

import java.util.Map;

/**
 * 工具权限决策。
 * 允许表示继续执行原工具；拒绝表示阻断；询问表示返回待人工确认的结构化载荷。
 */
public record ToolPermissionDecision(
        Behavior behavior,
        String reason,
        Map<String, Object> payload
) {

    public enum Behavior {
        ALLOW,
        DENY,
        ASK
    }

    public static ToolPermissionDecision allow() {
        return new ToolPermissionDecision(Behavior.ALLOW, "", Map.of());
    }

    public static ToolPermissionDecision deny(String reason) {
        return new ToolPermissionDecision(Behavior.DENY,
                reason == null || reason.isBlank() ? "工具调用被拒绝" : reason,
                Map.of());
    }

    public static ToolPermissionDecision ask(Map<String, Object> payload) {
        return new ToolPermissionDecision(Behavior.ASK, "", payload == null ? Map.of() : payload);
    }
}
