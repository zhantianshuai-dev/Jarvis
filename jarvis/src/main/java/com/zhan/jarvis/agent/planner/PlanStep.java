package com.zhan.jarvis.agent.planner;

import java.util.List;

/**
 * 单个执行计划步骤。
 */
public record PlanStep(
        String id,
        String title,
        PlanStepType type,
        PlanStepStatus status,
        List<String> toolGroups,
        boolean parallel,
        String parallelGroup,
        boolean requiresConfirmation,
        String reason
) {
    public PlanStep withStatus(PlanStepStatus nextStatus) {
        return new PlanStep(id, title, type, nextStatus, toolGroups, parallel, parallelGroup,
                requiresConfirmation, reason);
    }
}
