package com.zhan.jarvis.session;

import com.zhan.jarvis.memory.MemoryServiceClient;

import java.util.Map;

/**
 * 前端聊天消息过滤器。
 * 只允许用户可理解的消息进入展示历史；工具调用、工具结果、隐藏上下文和追踪事件只保留在 JSONL 中。
 */
public class ChatDisplayMessageFilter {

    public boolean visible(MemoryServiceClient.SessionMessage message) {
        if (message == null) {
            return false;
        }
        Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
        if (truthy(metadata.get("trace")) || truthy(metadata.get("hidden"))
                || explicitlyFalse(metadata.get("display_event"))) {
            return false;
        }
        if (message.content() == null || message.content().isBlank()) {
            return false;
        }
        return switch (message.role()) {
            case "user" -> true;
            case "assistant" -> assistantVisible(metadata);
            default -> false;
        };
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
