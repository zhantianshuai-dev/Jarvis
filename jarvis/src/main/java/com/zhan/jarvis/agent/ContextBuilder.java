package com.zhan.jarvis.agent;

import com.zhan.jarvis.config.JarvisConfig;
import com.zhan.jarvis.agent.context.ContextBuildRequest;
import com.zhan.jarvis.agent.context.ContextPipeline;
import com.zhan.jarvis.agent.context.CurrentMessageStage;
import com.zhan.jarvis.agent.context.DynamicReminderStage;
import com.zhan.jarvis.agent.context.MemoryRetrievalStage;
import com.zhan.jarvis.agent.context.SessionHistoryStage;
import com.zhan.jarvis.agent.context.SkillInjectionStage;
import com.zhan.jarvis.agent.context.SystemPromptStage;
import com.zhan.jarvis.agent.context.ToolExposureStage;
import com.zhan.jarvis.llm.Message;
import com.zhan.jarvis.memory.MemoryServiceClient;
import com.zhan.jarvis.session.Session;
import com.zhan.jarvis.skill.SkillsLoader;
import com.zhan.jarvis.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 上下文构建器 — 组装 LLM 调用所需的完整消息列表。
 * 消息历史和工作记忆均从 memory-service 获取，Jarvis 不自己管理上下文。
 */
public class ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    private final JarvisConfig.AgentConfig agentConfig;
    private final ContextPipeline pipeline;

    public ContextBuilder(JarvisConfig.AgentConfig agentConfig, ToolRegistry toolRegistry,
                          MemoryServiceClient memoryClient, SkillsLoader skillsLoader) {
        this.agentConfig = agentConfig;
        var toolExposureStage = new ToolExposureStage(toolRegistry);
        var skillInjectionStage = new SkillInjectionStage(skillsLoader);
        this.pipeline = new ContextPipeline(List.of(
                new SystemPromptStage(agentConfig, toolExposureStage, skillInjectionStage),
                new DynamicReminderStage(),
                new SessionHistoryStage(memoryClient),
                new MemoryRetrievalStage(memoryClient),
                new CurrentMessageStage()
        ));
    }

    /**
     * 构建完整消息列表。
     */
    public List<Message> build(Session session, String currentMessage, int historyRounds) {
        return build(session, currentMessage, historyRounds, RunMode.AGENT);
    }

    /**
     * 按运行模式构建完整消息列表。
     * /chat 模式只保留对话能力，不注入工具摘要和技能加载提示。
     */
    public List<Message> build(Session session, String currentMessage, int historyRounds, RunMode runMode) {
        return build(session, currentMessage, historyRounds, runMode, agentConfig.workspace());
    }

    /**
     * 按运行模式和当前工作目录构建完整消息列表。
     */
    public List<Message> build(Session session, String currentMessage, int historyRounds,
                               RunMode runMode, String workspace) {
        RunMode mode = runMode != null ? runMode : RunMode.AGENT;
        var messages = pipeline.build(new ContextBuildRequest(session, currentMessage, historyRounds, mode, workspace));
        log.debug("ContextBuilder: 构建 {} 条消息", messages.size());
        return messages;
    }
}
