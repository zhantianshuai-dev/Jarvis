package com.zhan.jarvis.agent.planner;

import com.zhan.jarvis.agent.RunMode;

import java.time.Instant;
import java.util.List;

/**
 * 一次 Agent 运行的结构化执行计划。
 */
public record ExecutionPlan(
        String planId,
        String runId,
        String sessionId,
        RunMode runMode,
        String goal,
        String strategy,
        boolean generatedByLlm,
        List<PlanStep> steps,
        Instant createdAt
) {
    public boolean hasSteps() {
        return steps != null && !steps.isEmpty();
    }
}
