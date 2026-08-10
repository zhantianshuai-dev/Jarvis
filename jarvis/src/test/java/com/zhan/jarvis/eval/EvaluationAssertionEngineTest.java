package com.zhan.jarvis.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationAssertionEngineTest {

    private final EvaluationAssertionEngine engine = new EvaluationAssertionEngine(new ObjectMapper());

    @TempDir
    Path tempDir;

    @Test
    void evaluatesFileAndToolSafetyAssertionsWithoutLlmJudge() throws Exception {
        Files.writeString(tempDir.resolve("result.txt"), "completed");
        var scenario = new EvaluationScenario("case", "case", "agent", "files", "prompt", List.of(
                new EvaluationAssertion("done", "", "", 0),
                new EvaluationAssertion("has_tool", "write_file", "", 0),
                new EvaluationAssertion("file_equals", "completed", "result.txt", 0),
                new EvaluationAssertion("tool_result_contains", "content_omitted", "write_file", 0),
                new EvaluationAssertion("tool_result_max_chars", "", "write_file", 100),
                new EvaluationAssertion("max_total_tokens", "", "", 300)
        ));
        var events = List.<Map<String, Object>>of(
                Map.of("type", "tool_call", "tool_name", "write_file", "arguments", "{}", "elapsed_ms", 10),
                Map.of("type", "tool_result", "tool_name", "write_file", "content", "{\"content_omitted\":true}", "elapsed_ms", 20),
                Map.of("type", "done", "content", "ok", "iteration", 1,
                        "token_usage", Map.of("prompt_tokens", 100, "completion_tokens", 50, "total_tokens", 150),
                        "requires_confirmation", false, "max_iterations_reached", false, "elapsed_ms", 30)
        );

        var result = engine.evaluate(scenario, events, tempDir, 30);

        assertThat(result.passed()).isTrue();
        assertThat(result.metrics()).containsEntry("total_tokens", 150L).containsEntry("tool_calls", 1);
    }

    @Test
    void detectsUnsafeGitArgumentPrefix() {
        var scenario = new EvaluationScenario("case", "case", "agent", "empty", "prompt", List.of(
                new EvaluationAssertion("no_tool_argument_prefix", "-", "git|target", 0)
        ));
        var events = List.<Map<String, Object>>of(
                Map.of("type", "tool_call", "tool_name", "git", "arguments", "{\"target\":\"--cached\"}")
        );

        var result = engine.evaluate(scenario, events, tempDir, 1);

        assertThat(result.passed()).isFalse();
    }

    @Test
    void passesWhenSimpleTaskHasNoPlannerEvent() {
        var scenario = new EvaluationScenario("case", "case", "agent", "empty", "prompt", List.of(
                new EvaluationAssertion("no_plan_event", "", "", 0)
        ));

        var result = engine.evaluate(scenario, List.of(Map.of("type", "done", "content", "ok")), tempDir, 1);

        assertThat(result.functionalPassed()).isTrue();
    }
}
