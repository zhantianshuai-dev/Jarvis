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
        state.messages().add(Message.system(buildSystemPrompt(request)));
    }

    private String buildSystemPrompt(ContextBuildRequest request) {
        RunMode mode = request.runMode();

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
                .replace("{tool_summary}", toolExposureStage.buildToolSummary(request)));

        String deferredToolsSection = toolExposureStage.buildDeferredToolsSection(request);
        if (!deferredToolsSection.isBlank()) {
            sb.append("\n\n").append(deferredToolsSection);
        }

        String skillsSection = skillInjectionStage.buildSkillsSection();
        if (!skillsSection.isBlank()) {
            sb.append("\n\n").append(skillsSection);
        }

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
