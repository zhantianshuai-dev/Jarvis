package com.zhan.jarvis.todo;

/**
 * Agent 在一次会话中维护的任务计划项。
 */
public record TodoItem(
        String id,
        String content,
        TodoStatus status,
        int order,
        String createdAt,
        String updatedAt
) {
}
