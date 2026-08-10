package com.zhan.jarvis.agent.context;

import com.zhan.jarvis.agent.RunMode;
import com.zhan.jarvis.config.JarvisConfig;
import com.zhan.jarvis.llm.Message;

public class SystemPromptStage implements ContextStage {

    private final JarvisConfig.AgentConfig agentConfig;
    private final ToolExposureStage toolExposureStage;
    private final SkillInjectionStage skillInjectionStage;

    public SystemPromptStage(JarvisConfig.AgentConfig agentConfig,
                             ToolExposureStage toolExposureStage,
                             SkillInjectionStage skillInjectionStage) {
        this.agentConfig = agentConfig;
        this.toolExposureStage = toolExposureStage;
        this.skillInjectionStage = skillInjectionStage;
    }

    @Override
    public void apply(ContextBuildRequest request, ContextBuildState state) {
        state.messages().add(Message.system(buildStaticSystemPrompt(request.runMode())));
        if (request.runMode() == RunMode.CHAT) {
            return;
        }

        String toolSummary = toolExposureStage.buildToolSummary(request);
        String deferredTools = toolExposureStage.buildDeferredToolsSection(request);
        if (!toolSummary.isBlank() || !deferredTools.isBlank()) {
            state.messages().add(Message.system("<runtime_tool_catalog>\n"
                    + toolSummary
                    + (toolSummary.isBlank() || deferredTools.isBlank() ? "" : "\n\n")
                    + deferredTools
                    + "\n</runtime_tool_catalog>"));
        }

        String skillsSection = skillInjectionStage.buildSkillsSection();
        if (!skillsSection.isBlank()) {
            state.messages().add(Message.system("<runtime_skills>\n" + skillsSection + "\n</runtime_skills>"));
        }
    }

    /**
     * 仅包含运行模式和固定规则，刻意不混入当前请求、工作目录、工具选择结果或检索记忆。
     * 这样同一会话多次调用模型时可以复用稳定前缀的缓存。
     */
    private String buildStaticSystemPrompt(RunMode mode) {

        if (mode == RunMode.CHAT) {
            return """
                    你叫{name}，是一个 AI 个人助手。
                    当前为 /chat 模式：你只能进行普通对话、解释概念、回答一般问题。
                    不要调用、模拟或声称已经调用任何工具；不要读取文件、列目录、执行命令或访问外部系统。
                    如果用户要求查看文件、执行命令、检索外部信息或修改项目，请说明需要切换到 /agent 或 /super-agent 模式。
                    """.replace("{name}", agentConfig.name())
                    .strip();
        }

        String template = agentConfig.systemPromptTemplate();
        if (template == null || template.isBlank()) {
            template = "你是 Jarvis，一个具有工具调用能力的 AI 助手。";
        }

        var sb = new StringBuilder();
        sb.append(removeDynamicPlaceholderLines(template)
                .replace("{workspace}", "")
                .replace("{name}", agentConfig.name())
                .replace("{now}", "")
                .replace("{tool_summary}", ""));

        sb.append("""

                <planning>
                对复杂、多步骤、需要修改文件、需要调用多个工具或需要派生子 Agent 的任务，先用 todo_update 建立简短 Todo 列表。
                Todo 是当前任务的实时执行状态，不是一次性计划；如果发现计划不合理，应调用 todo_update 调整、删除或新增步骤。
                开始执行某个步骤时标记为 in_progress；确认完成后立即标记为 completed；受阻或失败时标记为 failed 并继续处理可完成的部分。
                如果存在并行子任务，可以同时保留多个 in_progress；如果是串行任务，通常只保留一个 in_progress。
                只有所有必要 Todo 已完成或明确失败后，才能给用户最终答复。
                普通问答、概念解释和单步小任务不要创建 Todo。
                </planning>""");

        return sb.toString();
    }

    private String removeDynamicPlaceholderLines(String template) {
        var sb = new StringBuilder();
        for (String line : template.split("\\R", -1)) {
            if (line.contains("{now}") || line.contains("{workspace}")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
