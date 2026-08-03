package com.zhan.jarvis.agent.planner;

/**
 * 执行计划步骤类型。
 */
public enum PlanStepType {
    THINK("think"),
    TOOL("tool"),
    AGENT("agent"),
    CONFIRM("confirm"),
    FINAL("final");

    private final String value;

    PlanStepType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static PlanStepType from(Object raw) {
        if (raw == null) {
            return THINK;
        }
        String text = String.valueOf(raw).strip().toLowerCase().replace("-", "_");
        return switch (text) {
            case "tool" -> TOOL;
            case "agent", "subagent", "sub_agent" -> AGENT;
            case "confirm", "confirmation" -> CONFIRM;
            case "final", "answer", "summary" -> FINAL;
            default -> THINK;
        };
    }
}
