package com.zhan.jarvis.agent.planner;

import com.zhan.jarvis.agent.RunMode;
import com.zhan.jarvis.config.JarvisConfig;
import com.zhan.jarvis.llm.AgentLLMProvider;
import com.zhan.jarvis.llm.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 执行计划生成器。
 * 先用规则判断是否需要规划；需要时调用 LLM 输出结构化计划，失败时使用确定性兜底计划。
 */
public class Planner {

    private static final Logger log = LoggerFactory.getLogger(Planner.class);
    private static final int MAX_STEPS = 8;

    private final JarvisConfig.PlannerConfig config;
    private final AgentLLMProvider llmProvider;
    private final ObjectMapper objectMapper;
    private final PlannerGate gate;

    public Planner(JarvisConfig.PlannerConfig config, AgentLLMProvider llmProvider, ObjectMapper objectMapper) {
        this.config = config;
        this.llmProvider = llmProvider;
        this.objectMapper = objectMapper;
        this.gate = new PlannerGate(config);
    }

    public boolean shouldPlan(RunMode mode, String message) {
        return gate.shouldPlan(mode, message);
    }

    public ExecutionPlan plan(String runId, String sessionId, RunMode mode, String userMessage, String workspace) {
        if (config == null || !config.enabled() || mode == RunMode.CHAT) {
            return null;
        }
        if (!shouldPlan(mode, userMessage)) {
            return null;
        }
        try {
            var response = llmProvider.chat(List.of(
                    Message.system(systemPrompt()),
                    Message.user(userPrompt(mode, userMessage, workspace))
            ), List.of());
            //对Planner输出的结果进行解析，填入到我们的对象中
            ExecutionPlan plan = parsePlan(runId, sessionId, mode, response.content());
            if (plan != null && plan.hasSteps()) {
                return plan;
            }
        } catch (Exception e) {
            log.warn("[Planner] LLM 规划失败，使用兜底计划: {}", e.getMessage());
            log.debug("[Planner] LLM 规划失败详情", e);
        }
        return fallbackPlan(runId, sessionId, mode, userMessage);
    }

    public String renderForContext(ExecutionPlan plan) {
        if (plan == null || !plan.hasSteps()) {
            return "";
        }
        var sb = new StringBuilder();
        sb.append("<execution_plan plan_id=\"").append(plan.planId()).append("\">\n");
        sb.append("<goal>").append(escape(plan.goal())).append("</goal>\n");
        if (plan.strategy() != null && !plan.strategy().isBlank()) {
            sb.append("<strategy>").append(escape(plan.strategy())).append("</strategy>\n");
        }
        for (PlanStep step : plan.steps()) {
            sb.append("<step id=\"").append(step.id())
                    .append("\" status=\"").append(step.status().value())
                    .append("\" type=\"").append(step.type().value())
                    .append("\" parallel=\"").append(step.parallel())
                    .append("\" requires_confirmation=\"").append(step.requiresConfirmation())
                    .append("\" tool_groups=\"").append(String.join(",", step.toolGroups()))
                    .append("\">")
                    .append(escape(step.title()))
                    .append("</step>\n");
        }
        sb.append("""
                <instruction>
                按 execution_plan 推进任务。优先处理第一个 in_progress 或 pending 步骤。
                每完成、失败或切换步骤时调用 todo_update 同步状态。
                对 requires_confirmation=true 的步骤，不要绕过人工确认。
                如果发现计划不合理，可以在说明原因后调整 todo，但不要忽略用户目标。
                </instruction>
                """);
        sb.append("</execution_plan>");
        return sb.toString();
    }

    private String systemPrompt() {
        return """
                你是 Jarvis 的任务规划器。你的唯一职责是把复杂用户请求拆成可执行计划。
                只输出 JSON，不要输出 Markdown，不要解释。
                计划必须保守、可执行、步骤少而清晰。

                可用步骤 type:
                - think: 阅读、分析、确认策略
                - tool: 需要主 Agent 调用工具
                - agent: 适合派生子 Agent 的独立子任务
                - confirm: 需要人工确认的高风险步骤
                - final: 汇总结果并回复用户

                可用 tool_groups:
                memory, file, exec, git, web, subagent, cron, feishu, image, mcp, planner

                输出 JSON schema:
                {
                  "goal": "一句话目标",
                  "strategy": "一句话执行策略",
                  "steps": [
                    {
                      "id": "step-1",
                      "title": "简短步骤标题",
                      "type": "think|tool|agent|confirm|final",
                      "tool_groups": ["file", "git"],
                      "parallel": false,
                      "parallel_group": "",
                      "requires_confirmation": false,
                      "reason": "为什么需要这一步"
                    }
                  ]
                }

                约束:
                - 最多 8 步。
                - 第一步通常是理解范围或查看状态，不要直接做高风险修改。
                - git push、merge、删除、覆盖、大范围写入必须 requires_confirmation=true。
                - 有依赖的步骤不要标 parallel=true。
                - 只有互相独立的分析任务才可标 parallel=true。
                """;
    }

    private String userPrompt(RunMode mode, String userMessage, String workspace) {
        return """
                mode: %s
                workspace: %s

                user_request:
                %s
                """.formatted(mode != null ? mode.value() : "agent",
                workspace == null ? "" : workspace,
                userMessage == null ? "" : userMessage).strip();
    }

    private ExecutionPlan parsePlan(String runId, String sessionId, RunMode mode, String content) throws Exception {
        String json = extractJson(content);
        if (json.isBlank()) {
            return null;
        }
        JsonNode root = objectMapper.readTree(json);
        String goal = root.path("goal").asText("");
        String strategy = root.path("strategy").asText("");
        var steps = parseSteps(root.path("steps"));
        if (steps.isEmpty()) {
            return null;
        }
        return new ExecutionPlan(newPlanId(), runId, sessionId, mode, safeGoal(goal, content), strategy,
                true, activateFirst(steps), Instant.now());
    }

    private List<PlanStep> parseSteps(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        var steps = new ArrayList<PlanStep>();
        int index = 1;
        for (JsonNode node : array) {
            if (steps.size() >= MAX_STEPS) {
                break;
            }
            String title = node.path("title").asText("").strip();
            if (title.isBlank()) {
                continue;
            }
            String id = node.path("id").asText("step-" + index).strip();
            var groups = new LinkedHashSet<String>();
            JsonNode groupNode = node.path("tool_groups");
            if (groupNode.isArray()) {
                for (JsonNode item : groupNode) {
                    String group = item.asText("").strip();
                    if (!group.isBlank()) {
                        groups.add(group);
                    }
                }
            }
            steps.add(new PlanStep(
                    id.isBlank() ? "step-" + index : id,
                    title,
                    PlanStepType.from(node.path("type").asText("think")),
                    PlanStepStatus.PENDING,
                    List.copyOf(groups),
                    node.path("parallel").asBoolean(false),
                    node.path("parallel_group").asText(""),
                    node.path("requires_confirmation").asBoolean(false),
                    node.path("reason").asText("")
            ));
            index++;
        }
        return List.copyOf(steps);
    }

    private ExecutionPlan fallbackPlan(String runId, String sessionId, RunMode mode, String userMessage) {
        var steps = new ArrayList<PlanStep>();
        steps.add(step("step-1", "明确任务范围并查看必要上下文", PlanStepType.THINK, List.of("memory")));
        if (containsAny(userMessage, "文件", "代码", "修改", "实现", "重构", "项目", "jarvis", "memory-service")) {
            steps.add(step("step-2", "读取相关文件并定位改动点", PlanStepType.TOOL, List.of("file")));
        }
        if (containsAny(userMessage, "测试", "编译", "运行", "验证", "test", "build")) {
            steps.add(step("step-3", "运行必要的验证命令", PlanStepType.TOOL, List.of("exec")));
        }
        if (containsAny(userMessage, "git", "提交", "推送", "commit", "push")) {
            steps.add(new PlanStep("step-4", "检查 Git 状态并准备提交", PlanStepType.TOOL,
                    PlanStepStatus.PENDING, List.of("git"), false, "", false, "涉及仓库状态"));
            steps.add(new PlanStep("step-5", "执行提交或推送前请求人工确认", PlanStepType.CONFIRM,
                    PlanStepStatus.PENDING, List.of("git"), false, "", true, "远程或提交操作需要确认"));
        }
        steps.add(step("step-final", "汇总执行结果并回复用户", PlanStepType.FINAL, List.of()));
        return new ExecutionPlan(newPlanId(), runId, sessionId, mode,
                safeGoal(userMessage, userMessage), "使用规则兜底计划推进任务。", false,
                activateFirst(steps), Instant.now());
    }

    private PlanStep step(String id, String title, PlanStepType type, List<String> groups) {
        return new PlanStep(id, title, type, PlanStepStatus.PENDING, groups, false, "", false, "");
    }

    private List<PlanStep> activateFirst(List<PlanStep> steps) {
        if (steps.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<PlanStep>();
        for (int i = 0; i < steps.size(); i++) {
            result.add(i == 0 ? steps.get(i).withStatus(PlanStepStatus.IN_PROGRESS) : steps.get(i));
        }
        return List.copyOf(result);
    }

    private String extractJson(String content) {
        if (content == null) {
            return "";
        }
        String text = content.strip();
        if (text.startsWith("```")) {
            int first = text.indexOf('{');
            int last = text.lastIndexOf('}');
            return first >= 0 && last > first ? text.substring(first, last + 1) : "";
        }
        int first = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return text.substring(first, last + 1);
        }
        return text;
    }

    private boolean containsAny(String text, String... words) {
        String safe = text == null ? "" : text.toLowerCase();
        for (String word : words) {
            if (safe.contains(word.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String safeGoal(String value, String fallback) {
        String text = value == null || value.isBlank() ? fallback : value;
        if (text == null || text.isBlank()) {
            return "完成用户请求";
        }
        text = text.strip().replaceAll("\\s+", " ");
        return text.length() <= 120 ? text : text.substring(0, 120) + "...";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String newPlanId() {
        return "plan_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
