package com.zhan.jarvis.eval;

import com.zhan.jarvis.agent.AgentLoop;
import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.server.sse.SseEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行固定的低成本 Agent 评测集，并生成可提交到 CI 工件的离线报告。
 */
@Component
@Profile("eval")
public class EvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);
    private static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());

    private final AgentLoop agentLoop;
    private final EvaluationProperties properties;
    private final ObjectMapper objectMapper;
    private final EvaluationAssertionEngine assertionEngine;

    public EvaluationRunner(AgentLoop agentLoop, EvaluationProperties properties, ObjectMapper objectMapper) {
        this.agentLoop = agentLoop;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.assertionEngine = new EvaluationAssertionEngine(objectMapper);
    }

    /**
     * @return 全部案例通过返回 0；出现评测失败或配置错误返回非零，便于 CI 使用。
     */
    public int runAll() {
        if (!properties.enabled()) {
            log.warn("Jarvis eval 未启用，跳过执行");
            return 0;
        }

        String runId = "eval-" + RUN_ID_FORMAT.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path reportDir = Path.of(properties.reportDir()).toAbsolutePath().normalize().resolve(runId);
        var workspace = new EvaluationWorkspace(properties.workspaceDir());
        var outcomes = new ArrayList<EvaluationRunOutcome>();
        long totalTokens = 0;
        boolean budgetExhausted = false;

        try {
            Files.createDirectories(reportDir);
            List<EvaluationScenario> scenarios = loadScenarios();
            if (scenarios.isEmpty()) {
                throw new IllegalStateException("未加载到评测场景");
            }
            log.info("开始 Agent 评测: runId={}, scenarios={}, attempts={}, tokenBudget={}",
                    runId, scenarios.size(), properties.normalizedRunsPerScenario(), properties.normalizedMaxTotalTokens());

            int sequence = 0;
            outer:
            for (var scenario : scenarios) {
                for (int attempt = 1; attempt <= properties.normalizedRunsPerScenario(); attempt++) {
                    if (totalTokens + scenarioTokenCeiling(scenario) > properties.normalizedMaxTotalTokens()) {
                        budgetExhausted = true;
                        break outer;
                    }
                    sequence++;
                    var outcome = runScenario(runId, sequence, scenario, attempt, workspace);
                    outcomes.add(outcome);
                    totalTokens += longValue(outcome.metrics().get("total_tokens"));
                    writeCaseReport(reportDir, outcome);
                    log.info("评测案例完成: {}/{} {}#{} status={}, tokens={}, elapsed={}ms",
                            sequence, scenarios.size() * properties.normalizedRunsPerScenario(), scenario.id(), attempt,
                            outcome.status(), outcome.metrics().get("total_tokens"), outcome.metrics().get("elapsed_ms"));
                }
            }

            var summary = buildSummary(runId, scenarios.size(), outcomes, totalTokens, budgetExhausted);
            writeSummary(reportDir, summary, outcomes);
            log.info("Agent 评测结束: runId={}, passed={}/{}, totalTokens={}, report={}",
                    runId, summary.passedRuns(), summary.executedRuns(), totalTokens, reportDir);
            return summary.functionalFailedRuns() == 0 && summary.performanceFailedRuns() == 0 && !budgetExhausted ? 0 : 1;
        } catch (Exception e) {
            log.error("Agent 评测启动或执行失败: {}", e.getMessage(), e);
            try {
                writeFatalReport(reportDir, runId, e);
            } catch (Exception reportError) {
                log.warn("写入评测失败报告失败: {}", reportError.getMessage());
            }
            return 2;
        }
    }

    private EvaluationRunOutcome runScenario(String runId, int sequence, EvaluationScenario rawScenario,
                                              int attempt, EvaluationWorkspace workspace) throws IOException {
        String outsideProbe = "jarvis-eval-outside-" + runId + "-" + sequence + ".txt";
        EvaluationScenario scenario = renderScenario(rawScenario, outsideProbe);
        workspace.prepare(scenario.fixture());
        workspace.clearOutsideProbe(outsideProbe);

        String sessionId = "eval_" + runId.replace('-', '_') + "_" + sequence + "_" + attempt;
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("mode", scenario.mode());
        metadata.put("workspace", "eval");
        metadata.put("evaluation", true);
        metadata.put("evaluation_run_id", runId);
        metadata.put("evaluation_scenario", scenario.id());
        metadata.put("evaluation_max_iterations", 4);

        long startedAt = System.nanoTime();
        List<Map<String, Object>> events;
        try {
            events = agentLoop.runStreaming(new SessionKey("evaluation", "local", sessionId), sessionId,
                            scenario.prompt(), "admin", metadata)
                    .map(event -> withElapsed(event, startedAt))
                    .timeout(Duration.ofSeconds(properties.normalizedRunTimeoutSeconds()))
                    .collectList()
                    .block();
            if (events == null) {
                events = List.of(errorEvent(sessionId, "评测流没有返回事件", startedAt));
            }
        } catch (Exception e) {
            events = List.of(errorEvent(sessionId, "评测执行超时或异常: " + safe(e.getMessage()), startedAt));
        }

        long elapsedMs = elapsedMillis(startedAt);
        var evaluation = assertionEngine.evaluate(scenario, events, workspace.root(), elapsedMs);
        String status = evaluation.functionalPassed()
                ? (evaluation.performancePassed() ? "passed" : "performance_failed")
                : "failed";
        return new EvaluationRunOutcome(
                scenario.id(), scenario.title(), attempt, status, scenario.mode(), scenario.prompt(),
                evaluation.functionalPassed(), evaluation.performancePassed(), evaluation.assertions(),
                evaluation.metrics(), compactEvents(events)
        );
    }

    private List<EvaluationScenario> loadScenarios() throws IOException {
        var resource = new ClassPathResource("evals/scenarios.json");
        try (var input = resource.getInputStream()) {
            EvaluationScenario[] items = objectMapper.readValue(input, EvaluationScenario[].class);
            List<EvaluationScenario> scenarios = items == null ? List.of() : List.of(items);
            var selected = properties.selectedScenarioIds();
            if (selected.isEmpty()) {
                return scenarios;
            }
            return scenarios.stream().filter(scenario -> selected.contains(scenario.id())).toList();
        }
    }

    private EvaluationScenario renderScenario(EvaluationScenario source, String outsideProbe) {
        var assertions = source.safeAssertions().stream()
                .map(item -> new EvaluationAssertion(
                        render(item.type(), outsideProbe),
                        render(item.expected(), outsideProbe),
                        render(item.path(), outsideProbe),
                        item.max()))
                .toList();
        return new EvaluationScenario(source.id(), source.title(), source.mode(), source.fixture(),
                render(source.prompt(), outsideProbe), assertions);
    }

    private String render(String text, String outsideProbe) {
        return safe(text).replace("{{outsideProbe}}", outsideProbe);
    }

    private Map<String, Object> withElapsed(Map<String, Object> source, long startedAt) {
        var event = new LinkedHashMap<String, Object>(source == null ? Map.of() : source);
        event.put("elapsed_ms", elapsedMillis(startedAt));
        return event;
    }

    private Map<String, Object> errorEvent(String sessionId, String message, long startedAt) {
        var event = new LinkedHashMap<String, Object>();
        event.put("type", SseEventTypes.ERROR);
        event.put("session_id", sessionId);
        event.put("content", message);
        event.put("elapsed_ms", elapsedMillis(startedAt));
        return event;
    }

    /** Token 和 reasoning 分片仅用于计算时延，不写入报告，以避免轨迹文件膨胀。 */
    private List<Map<String, Object>> compactEvents(List<Map<String, Object>> events) {
        return events.stream()
                .filter(event -> !SseEventTypes.TOKEN.equals(event.get("type")))
                .filter(event -> !SseEventTypes.REASONING.equals(event.get("type")))
                .toList();
    }

    private EvaluationSummary buildSummary(String runId, int scenarioCount, List<EvaluationRunOutcome> outcomes,
                                           long totalTokens, boolean budgetExhausted) {
        int passed = (int) outcomes.stream().filter(EvaluationRunOutcome::functionalPassed).count();
        int functionalFailed = (int) outcomes.stream().filter(outcome -> !outcome.functionalPassed()).count();
        int performanceFailed = (int) outcomes.stream()
                .filter(EvaluationRunOutcome::functionalPassed)
                .filter(outcome -> !outcome.performancePassed())
                .count();
        long totalElapsed = outcomes.stream().mapToLong(outcome -> longValue(outcome.metrics().get("elapsed_ms"))).sum();
        long totalLlmCalls = outcomes.stream().mapToLong(outcome -> longValue(outcome.metrics().get("llm_calls"))).sum();
        long totalToolCalls = outcomes.stream().mapToLong(outcome -> longValue(outcome.metrics().get("tool_calls"))).sum();
        return new EvaluationSummary(runId, scenarioCount, properties.normalizedRunsPerScenario(), outcomes.size(),
                passed, functionalFailed, performanceFailed, totalTokens, average(totalTokens, outcomes.size()), average(totalElapsed, outcomes.size()),
                totalLlmCalls, totalToolCalls, budgetExhausted, Instant.now().toString());
    }

    private void writeCaseReport(Path reportDir, EvaluationRunOutcome outcome) throws IOException {
        String fileName = outcome.scenarioId() + "-" + outcome.attempt() + ".json";
        objectMapper.writeValue(reportDir.resolve(fileName).toFile(), outcome);
    }

    private void writeSummary(Path reportDir, EvaluationSummary summary,
                              List<EvaluationRunOutcome> outcomes) throws IOException {
        objectMapper.writeValue(reportDir.resolve("summary.json").toFile(), Map.of(
                "summary", summary,
                "outcomes", outcomes
        ));
        Files.writeString(reportDir.resolve("summary.md"), markdownSummary(summary, outcomes));
    }

    private void writeFatalReport(Path reportDir, String runId, Exception error) throws IOException {
        Files.createDirectories(reportDir);
        objectMapper.writeValue(reportDir.resolve("fatal.json").toFile(), Map.of(
                "run_id", runId,
                "error", safe(error.getMessage()),
                "created_at", Instant.now().toString()
        ));
    }

    private String markdownSummary(EvaluationSummary summary, List<EvaluationRunOutcome> outcomes) {
        var builder = new StringBuilder();
        builder.append("# Jarvis Agent Evaluation\n\n");
        builder.append("- Run: `").append(summary.runId()).append("`\n");
        builder.append("- Functional result: ").append(summary.passedRuns()).append('/').append(summary.executedRuns())
                .append(" passed").append(summary.budgetExhausted() ? " (token budget exhausted)" : "").append("\n");
        builder.append("- Functional failures: ").append(summary.functionalFailedRuns())
                .append(", performance threshold failures: ").append(summary.performanceFailedRuns()).append("\n");
        builder.append("- Tokens: total ").append(summary.totalTokens()).append(", average ")
                .append(summary.averageTokens()).append("\n");
        builder.append("- Average latency: ").append(summary.averageElapsedMs()).append(" ms\n");
        builder.append("- Tool calls: ").append(summary.totalToolCalls()).append("\n\n");
        builder.append("| Scenario | Attempt | Status | Tokens | Time | Failed assertions |\n");
        builder.append("| --- | ---: | --- | ---: | ---: | --- |\n");
        for (var outcome : outcomes) {
            String failed = outcome.assertions().stream().filter(item -> !item.passed())
                    .map(EvaluationAssertionEngine.AssertionResult::type).reduce((left, right) -> left + ", " + right)
                    .orElse("");
            builder.append('|').append(outcome.scenarioId()).append('|').append(outcome.attempt()).append('|')
                    .append(outcome.status()).append('|').append(outcome.metrics().get("total_tokens")).append('|')
                    .append(outcome.metrics().get("elapsed_ms")).append('|').append(failed).append("|\n");
        }
        return builder.toString();
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private static long average(long total, int count) {
        return count == 0 ? 0 : Math.round((double) total / count);
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(safe(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static long scenarioTokenCeiling(EvaluationScenario scenario) {
        return scenario.safeAssertions().stream()
                .filter(assertion -> "max_total_tokens".equals(assertion.type()))
                .map(EvaluationAssertion::max)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Integer::longValue)
                .max()
                .orElse(8_000L);
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record EvaluationRunOutcome(
            String scenarioId,
            String title,
            int attempt,
            String status,
            String mode,
            String prompt,
            boolean functionalPassed,
            boolean performancePassed,
            List<EvaluationAssertionEngine.AssertionResult> assertions,
            Map<String, Object> metrics,
            List<Map<String, Object>> events
    ) {
    }

    public record EvaluationSummary(
            String runId,
            int scenarioCount,
            int configuredAttempts,
            int executedRuns,
            int passedRuns,
            int functionalFailedRuns,
            int performanceFailedRuns,
            long totalTokens,
            long averageTokens,
            long averageElapsedMs,
            long totalLlmCalls,
            long totalToolCalls,
            boolean budgetExhausted,
            String completedAt
    ) {
    }
}
