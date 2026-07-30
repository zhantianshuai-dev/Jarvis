package com.zhan.memoryservice.session;

import java.util.Map;

/**
 * 会话消息可见性过滤器。
 * JSONL 中仍完整保存运行态消息；这里只决定哪些消息可以进入前端展示或下一轮运行态上下文。
 */
public class SessionMessageVisibilityFilter {

    public boolean visibleForDisplay(Message message) {
        if (message == null) {
            return false;
        }
        Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
        if (truthy(metadata.get("trace")) || truthy(metadata.get("hidden"))
                || explicitlyFalse(metadata.get("display_event"))) {
            return false;
        }
        String content = textOf(message);
        if (content.isBlank()) {
            return false;
        }
        return switch (message.role()) {
            case "user" -> true;
            case "assistant" -> assistantVisible(metadata);
            default -> false;
        };
    }

    public boolean visibleForRuntimeContext(Message message) {
        if (!visibleForDisplay(message)) {
            return false;
        }
        Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
        return !truthy(metadata.get("subagent_status"));
    }

    public String textOf(Message message) {
        if (message == null || message.parts() == null) {
            return "";
        }
        return message.parts().stream()
                .filter(part -> "text".equals(part.type()) && part.text() != null)
                .map(Message.Part::text)
                .reduce("", (a, b) -> a + b);
    }

    private boolean assistantVisible(Map<String, Object> metadata) {
        Object finalFlag = metadata.get("final");
        return finalFlag == null || truthy(finalFlag);
    }

    private boolean explicitlyFalse(Object value) {
        if (value == null) {
            return false;
        }
        return !Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean truthy(Object value) {
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
