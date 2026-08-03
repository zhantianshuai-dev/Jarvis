package com.zhan.jarvis.todo;

import java.util.Locale;

/**
 * Agent 计划项状态。
 */
public enum TodoStatus {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String value;

    TodoStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static TodoStatus from(Object raw) {
        if (raw == null) {
            return PENDING;
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT).replace("-", "_");
        return switch (text) {
            case "in_progress", "running", "doing" -> IN_PROGRESS;
            case "completed", "complete", "done", "success" -> COMPLETED;
            case "failed", "failure", "error" -> FAILED;
            default -> PENDING;
        };
    }
}
