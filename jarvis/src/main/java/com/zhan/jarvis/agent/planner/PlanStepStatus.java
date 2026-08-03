package com.zhan.jarvis.agent.planner;

/**
 * 执行计划步骤状态。
 */
public enum PlanStepStatus {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    FAILED("failed"),
    SKIPPED("skipped");

    private final String value;

    PlanStepStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static PlanStepStatus from(Object raw) {
        if (raw == null) {
            return PENDING;
        }
        String text = String.valueOf(raw).strip().toLowerCase().replace("-", "_");
        return switch (text) {
            case "in_progress", "running" -> IN_PROGRESS;
            case "completed", "done", "success" -> COMPLETED;
            case "failed", "error" -> FAILED;
            case "skipped", "skip" -> SKIPPED;
            default -> PENDING;
        };
    }
}
