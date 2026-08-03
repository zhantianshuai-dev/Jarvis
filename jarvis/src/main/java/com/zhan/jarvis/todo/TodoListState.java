package com.zhan.jarvis.todo;

import java.util.List;

/**
 * 一个会话当前的计划列表快照。
 */
public record TodoListState(
        String sessionId,
        String runId,
        List<TodoItem> items,
        String updatedAt
) {
}
