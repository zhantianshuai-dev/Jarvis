package com.zhan.jarvis.eval;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用规则断言评估一次 Agent 轨迹，避免额外使用 LLM 作为裁判而增加成本和不确定性。
 */
public class EvaluationAssertionEngine {

    private final ObjectMapper objectMapper;

    public EvaluationAssertionEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EvaluationResult evaluate(EvaluationScenario scenario, List<Map<String, Object>> events,
                                     Path workspace, long elapsedMs) {
        List<Map<String, Object>> safeEvents = events == null ? List.of() : List.copyOf(events);
        var metrics = metrics(safeEvents, elapsedMs);
        var assertions = new ArrayList<AssertionResult>();
        for (var assertion : scenario.safeAssertions()) {
            assertions.add(evaluateAssertion(assertion, safeEvents, workspace, metrics));
        }
        boolean functionalPassed = assertions.stream()
                .filter(assertion -> !isPerformanceAssertion(assertion.type()))
                .allMatch(AssertionResult::passed);
        boolean performancePassed = assertions.stream()
                .filter(assertion -> isPerformanceAssertion(assertion.type()))
                .allMatch(AssertionResult::passed);
        return new EvaluationResult(functionalPassed, performancePassed, List.copyOf(assertions), metrics);
    }

    private AssertionResult evaluateAssertion(EvaluationAssertion assertion, List<Map<String, Object>> events,
                                              Path workspace, Map<String, Object> metrics) {
        String type = safe(assertion.type());
        try {
            return switch (type) {
                case "done" -> result(type, doneEvent(events) != null && errorEvents(events).isEmpty(), "完成事件存在且没有错误事件");
                case "no_tool_calls" -> result(type, toolCalls(events).isEmpty(), "工具调用数=" + toolCalls(events).size());
                case "has_tool" -> result(type, hasTool(events, assertion.expected()), "期望工具=" + safe(assertion.expected()));
                case "no_tool" -> result(type, !hasTool(events, assertion.expected()), "禁止工具=" + safe(assertion.expected()));
                case "tool_before" -> toolBefore(events, assertion.expected());
                case "reply_contains" -> replyContains(events, assertion.expected());
                case "file_equals" -> fileEquals(workspace, assertion.path(), assertion.expected());
                case "file_contains" -> fileContains(workspace, assertion.path(), assertion.expected());
                case "path_outside_absent" -> outsideAbsent(workspace, assertion.expected());
                case "tool_result_contains" -> toolResultContains(events, assertion.path(), assertion.expected());
                case "tool_result_max_chars" -> toolResultMaxChars(events, assertion.path(), assertion.max());
                case "no_tool_argument_prefix" -> noToolArgumentPrefix(events, assertion.path(), assertion.expected());
                case "requires_confirmation" -> requiresConfirmation(events);
                case "has_plan_event" -> result(type, events.stream().anyMatch(event -> "plan_update".equals(eventType(event))), "plan_update 事件");
                case "no_plan_event" -> result(type, events.stream().noneMatch(event -> "plan_update".equals(eventType(event))), "没有 plan_update 事件");
                case "max_total_tokens" -> maxTotalTokens(metrics, assertion.max());
                default -> result(type, false, "未知断言类型");
            };
        } catch (Exception e) {
            return result(type, false, "断言执行异常: " + safe(e.getMessage()));
        }
    }

    private Map<String, Object> metrics(List<Map<String, Object>> events, long elapsedMs) {
        var metrics = new LinkedHashMap<String, Object>();
        Map<String, Object> done = doneEvent(events);
        Map<String, Object> usage = map(done == null ? null : done.get("token_usage"));
        metrics.put("prompt_tokens", number(usage.get("prompt_tokens")));
        metrics.put("completion_tokens", number(usage.get("completion_tokens")));
        metrics.put("total_tokens", number(usage.get("total_tokens")));
        metrics.put("iteration", number(done == null ? null : done.get("iteration")));
        metrics.put("tool_calls", toolCalls(events).size());
        metrics.put("tool_results", events.stream().filter(event -> "tool_result".equals(eventType(event))).count());
        // SSE 不透出 model.completed 审计事件；一次 loop iteration 对应一次主模型调用。
        metrics.put("llm_calls", number(done == null ? null : done.get("iteration")));
        metrics.put("elapsed_ms", elapsedMs);
        metrics.put("first_event_ms", firstEventMillis(events));
        metrics.put("first_token_ms", firstTokenMillis(events));
        metrics.put("requires_confirmation", done != null && booleanValue(done.get("requires_confirmation")));
        metrics.put("max_iterations_reached", done != null && booleanValue(done.get("max_iterations_reached")));
        return metrics;
    }

    private AssertionResult toolBefore(List<Map<String, Object>> events, String expected) {
        String[] names = safe(expected).split(">");
        if (names.length != 2) {
            return result("tool_before", false, "格式应为 first>second");
        }
        int first = toolIndex(events, names[0]);
        int second = toolIndex(events, names[1]);
        return result("tool_before", first >= 0 && second > first,
                "顺序=" + safe(names[0]) + "(" + first + ") > " + safe(names[1]) + "(" + second + ")");
    }

    private AssertionResult replyContains(List<Map<String, Object>> events, String expected) {
        Map<String, Object> done = doneEvent(events);
        String reply = done == null ? "" : safe(done.get("content"));
        return result("reply_contains", reply.toLowerCase(Locale.ROOT).contains(safe(expected).toLowerCase(Locale.ROOT)),
                "期望回复包含=" + safe(expected));
    }

    private AssertionResult fileEquals(Path workspace, String relativePath, String expected) throws Exception {
        Path file = resolveWorkspacePath(workspace, relativePath);
        boolean passed = Files.isRegularFile(file) && safe(Files.readString(file)).equals(safe(expected));
        return result("file_equals", passed, "文件=" + file.getFileName());
    }

    private AssertionResult fileContains(Path workspace, String relativePath, String expected) throws Exception {
        Path file = resolveWorkspacePath(workspace, relativePath);
        boolean passed = Files.isRegularFile(file) && Files.readString(file).contains(safe(expected));
        return result("file_contains", passed, "文件=" + file.getFileName() + ", 期望内容=" + safe(expected));
    }

    private AssertionResult outsideAbsent(Path workspace, String fileName) {
        Path parent = workspace.toAbsolutePath().normalize().getParent();
        Path target = parent == null ? workspace.resolve("..").resolve(fileName).normalize() : parent.resolve(fileName).normalize();
        return result("path_outside_absent", !Files.exists(target), "越界目标=" + target.getFileName());
    }

    private AssertionResult toolResultContains(List<Map<String, Object>> events, String toolName, String expected) {
        boolean passed = toolResults(events, toolName).stream()
                .map(event -> safe(event.get("content")))
                .anyMatch(content -> content.contains(safe(expected)));
        return result("tool_result_contains", passed, "工具=" + safe(toolName) + ", 期望=" + safe(expected));
    }

    private AssertionResult toolResultMaxChars(List<Map<String, Object>> events, String toolName, int max) {
        List<Map<String, Object>> results = toolResults(events, toolName);
        int actual = results.stream().mapToInt(event -> safe(event.get("content")).length()).max().orElse(0);
        return result("tool_result_max_chars", !results.isEmpty() && actual <= Math.max(1, max),
                "工具=" + safe(toolName) + ", 最大结果长度=" + actual + ", 限制=" + max);
    }

    private AssertionResult noToolArgumentPrefix(List<Map<String, Object>> events, String path, String prefix) {
        String[] parts = safe(path).split("\\|", 2);
        if (parts.length != 2) {
            return result("no_tool_argument_prefix", false, "path 格式应为 tool|field");
        }
        boolean violation = false;
        for (var event : toolCalls(events)) {
            if (!parts[0].equals(safe(event.get("tool_name")))) {
                continue;
            }
            String value = safe(toolArgument(event, parts[1]));
            if (value.startsWith(safe(prefix))) {
                violation = true;
                break;
            }
        }
        return result("no_tool_argument_prefix", !violation,
                "工具=" + parts[0] + ", 字段=" + parts[1] + ", 禁止前缀=" + safe(prefix));
    }

    private AssertionResult requiresConfirmation(List<Map<String, Object>> events) {
        Map<String, Object> done = doneEvent(events);
        return result("requires_confirmation", done != null && booleanValue(done.get("requires_confirmation")),
                "done.requires_confirmation=true");
    }

    private AssertionResult maxTotalTokens(Map<String, Object> metrics, int max) {
        long actual = number(metrics.get("total_tokens"));
        return result("max_total_tokens", actual > 0 && actual <= Math.max(1, max),
                "total_tokens=" + actual + ", 限制=" + max);
    }

    private List<Map<String, Object>> toolCalls(List<Map<String, Object>> events) {
        return events.stream().filter(event -> "tool_call".equals(eventType(event))).toList();
    }

    private List<Map<String, Object>> toolResults(List<Map<String, Object>> events, String toolName) {
        return events.stream()
                .filter(event -> "tool_result".equals(eventType(event)))
                .filter(event -> safe(toolName).equals(safe(event.get("tool_name"))))
                .toList();
    }

    private boolean hasTool(List<Map<String, Object>> events, String name) {
        return toolCalls(events).stream().anyMatch(event -> safe(name).equals(safe(event.get("tool_name"))));
    }

    private int toolIndex(List<Map<String, Object>> events, String toolName) {
        var tools = toolCalls(events);
        for (int i = 0; i < tools.size(); i++) {
            if (safe(toolName).equals(safe(tools.get(i).get("tool_name")))) {
                return i;
            }
        }
        return -1;
    }

    private Map<String, Object> doneEvent(List<Map<String, Object>> events) {
        return events.stream().filter(event -> "done".equals(eventType(event))).reduce((first, second) -> second).orElse(null);
    }

    private List<Map<String, Object>> errorEvents(List<Map<String, Object>> events) {
        return events.stream().filter(event -> "error".equals(eventType(event))).toList();
    }

    private long firstEventMillis(List<Map<String, Object>> events) {
        for (var event : events) {
            long value = number(event.get("elapsed_ms"));
            if (value > 0) return value;
        }
        return 0;
    }

    private long firstTokenMillis(List<Map<String, Object>> events) {
        for (var event : events) {
            if ("token".equals(eventType(event))) {
                return number(event.get("elapsed_ms"));
            }
        }
        return 0;
    }

    private Object toolArgument(Map<String, Object> event, String field) {
        Object raw = event.get("arguments");
        if (raw instanceof Map<?, ?> map) {
            return map.get(field);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(safe(raw), Map.class);
            return parsed == null ? null : parsed.get(field);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path resolveWorkspacePath(Path workspace, String relativePath) {
        Path root = workspace.toAbsolutePath().normalize();
        Path resolved = root.resolve(safe(relativePath)).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("断言路径越界: " + relativePath);
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source ? (Map<String, Object>) source : Map.of();
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(safe(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static boolean booleanValue(Object value) {
        return Boolean.parseBoolean(safe(value));
    }

    private static String eventType(Map<String, Object> event) {
        return safe(event == null ? null : event.get("type"));
    }

    private static AssertionResult result(String type, boolean passed, String detail) {
        return new AssertionResult(type, passed, detail);
    }

    private static boolean isPerformanceAssertion(String type) {
        return "max_total_tokens".equals(type) || "tool_result_max_chars".equals(type);
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record AssertionResult(String type, boolean passed, String detail) {
    }

    public record EvaluationResult(boolean functionalPassed, boolean performancePassed,
                                   List<AssertionResult> assertions, Map<String, Object> metrics) {
        public boolean passed() {
            return functionalPassed && performancePassed;
        }
    }
}
