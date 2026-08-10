package com.zhan.jarvis.eval;

import java.util.List;

/**
 * 单个评测任务。场景定义放在 resources/evals/scenarios.json，便于在不改代码的情况下增删任务。
 */
public record EvaluationScenario(
        String id,
        String title,
        String mode,
        String fixture,
        String prompt,
        List<EvaluationAssertion> assertions
) {
    public List<EvaluationAssertion> safeAssertions() {
        return assertions == null ? List.of() : assertions;
    }
}
