package com.zhan.jarvis.eval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 端到端评测配置。
 */
@ConfigurationProperties(prefix = "jarvis.eval")
public record EvaluationProperties(
        boolean enabled,
        int runsPerScenario,
        int maxTotalTokens,
        long runTimeoutSeconds,
        String reportDir,
        String workspaceDir,
        String scenarios
) {
    public int normalizedRunsPerScenario() {
        return Math.max(1, runsPerScenario);
    }

    public int normalizedMaxTotalTokens() {
        return Math.max(1, maxTotalTokens);
    }

    public long normalizedRunTimeoutSeconds() {
        return Math.max(10, runTimeoutSeconds);
    }

    public java.util.Set<String> selectedScenarioIds() {
        if (scenarios == null || scenarios.isBlank()) {
            return java.util.Set.of();
        }
        return java.util.Arrays.stream(scenarios.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
