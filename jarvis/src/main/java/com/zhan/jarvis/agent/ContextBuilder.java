package com.zhan.jarvis.agent;

import com.zhan.jarvis.config.JarvisConfig;
import com.zhan.jarvis.llm.Message;
import com.zhan.jarvis.memory.MemoryServiceClient;
import com.zhan.jarvis.session.Session;
import com.zhan.jarvis.skill.SkillsLoader;
import com.zhan.jarvis.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文构建器 — 组装 LLM 调用所需的完整消息列表。
 *
 * 对标 Python VikingBot ContextBuilder.build_messages()。
 * 消息历史和 Working Memory 均从 memory-service 获取，Jarvis 不自己管理上下文。
 */
public class ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    private final JarvisConfig.AgentConfig agentConfig;
    private final ToolRegistry toolRegistry;
    private final MemoryServiceClient memoryClient;
    private final SkillsLoader skillsLoader;

    public ContextBuilder(JarvisConfig.AgentConfig agentConfig, ToolRegistry toolRegistry,
                          MemoryServiceClient memoryClient, SkillsLoader skillsLoader) {
        this.agentConfig = agentConfig;
        this.toolRegistry = toolRegistry;
        this.memoryClient = memoryClient;
        this.skillsLoader = skillsLoader;
    }

    /**
     * 构建完整消息列表。
     */
    public List<Message> build(Session session, String currentMessage, int historyRounds) {
        var messages = new ArrayList<Message>();

        // 1. System prompt（含技能系统）
        messages.add(Message.system(buildSystemPrompt()));

        // 2. Session context（Working Memory + 最近消息，从 memory-service 获取）
        try {
            var ctx = memoryClient.getSessionContext(session.id(), historyRounds * 2);

            // Working Memory（对标 Jarvis latest_archive_overview）
            String wm = ctx.workingMemory();
            if (wm != null && !wm.isBlank()) {
                messages.add(Message.system("<working_memory>\n" + wm + "\n</working_memory>"));
            }

            // Conversation history（从 memory-service JSONL，带轮次限制）
            for (var stub : ctx.messages()) {
                messages.add(new Message(stub.role(), stub.content(), null, null, null));
            }
        } catch (Exception e) {
            log.debug("获取 Session Context 失败（非致命）: {}", e.getMessage());
        }

        // 3. Memory context（检索相关记忆）
        try {
            String memoryResult = memoryClient.search(currentMessage, 5);
            if (memoryResult != null && !memoryResult.isBlank() && !memoryResult.equals("{}")) {
                messages.add(Message.user(
                        "<memory_context>\n" + memoryResult + "\n</memory_context>\n\n" +
                        "以上是从记忆系统中检索到的相关上下文。请参考这些信息回复用户的以下消息：\n\n" +
                        currentMessage));
                return messages;
            }
        } catch (Exception e) {
            log.debug("记忆检索失败（非致命）: {}", e.getMessage());
        }

        // 4. 无记忆上下文时，效果就是直接插入当前消息
        messages.add(Message.user(currentMessage));

        log.debug("ContextBuilder: 构建 {} 条消息", messages.size());
        return messages;
    }

    /**
     * 构建系统提示词，动态注入工具摘要 + 技能系统（渐进式加载）。
     */
    private String buildSystemPrompt() {
        String template = agentConfig.systemPromptTemplate();
        if (template == null || template.isBlank()) {
            template = "你是 Jarvis，一个具有工具调用能力的 AI 助手。";
        }

        String now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));

        var sb = new StringBuilder();
        sb.append(template
                .replace("{workspace}", agentConfig.workspace())
                .replace("{name}", agentConfig.name())
                .replace("{now}", now)
                .replace("{tool_summary}", buildToolSummary()));

        // 技能系统：渐进式加载
        String skillsSection = buildSkillsSection();
        if (!skillsSection.isBlank()) {
            sb.append("\n\n").append(skillsSection);
        }

        return sb.toString();
    }

    /**
     * 构建技能段落 — 渐进式加载：
     * 1. always=true 的技能 → 全量注入
     * 2. 其他技能 → 注入摘要 XML（agent 可用 read_file 按需加载完整内容）
     *
     * 对标 Python ContextBuilder.build_system_prompt() 中的 skills 处理。
     */
    private String buildSkillsSection() {
        var parts = new ArrayList<String>();

        // 1. Always-loaded skills: 全量内容注入
        var alwaysSkills = skillsLoader.getAlwaysSkills();
        if (!alwaysSkills.isEmpty()) {
            String alwaysContent = skillsLoader.loadSkillsForContext(alwaysSkills);
            if (!alwaysContent.isBlank()) {
                parts.add("# Active Skills\n\n" + alwaysContent);
            }
        }

        // 2. Available skills: 只注入摘要 XML
        String summary = skillsLoader.buildSkillsSummary();
        if (!summary.isBlank()) {
            parts.add("# Available Skills\n\n"
                    + "以下技能可扩展你的能力。使用 read_file 工具读取 SKILL.md 获取完整内容。\n"
                    + "这里只列出依赖已满足且未被 always 全量加载的技能。\n\n"
                    + summary);
        }

        return String.join("\n\n---\n\n", parts);
    }

    /**
     * 从 ToolRegistry 动态生成工具摘要（对标 Jarvis identity 中的能力列表）。
     */
    private String buildToolSummary() {
        var tools = toolRegistry.listTools();
        if (tools.isEmpty()) return "";

        var sb = new StringBuilder("你拥有以下能力：\n");
        for (var td : tools) {
            sb.append("- ").append(td.name()).append(": ").append(td.description()).append("\n");
        }
        return sb.toString();
    }
}
