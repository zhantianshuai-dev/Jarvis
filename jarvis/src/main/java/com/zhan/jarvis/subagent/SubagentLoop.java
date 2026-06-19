package com.zhan.jarvis.subagent;

import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.llm.*;
import com.zhan.jarvis.memory.MemoryServiceClient;
import com.zhan.jarvis.tool.ToolContext;
import com.zhan.jarvis.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 子 Agent 独立循环 — 对标 Python VikingBot SubagentLoop。
 * <p>
 * 子 Agent 拥有:
 * - 受限工具集（无 spawn 递归）
 * - 独立迭代上限（15 轮）
 * - 独立 System Prompt
 */
public class SubagentLoop {

    private static final Logger log = LoggerFactory.getLogger(SubagentLoop.class);
    private static final int MAX_ITERATIONS = 10;

    private final String taskId;
    private final String task;
    private final ToolRegistry toolRegistry;
    private final AgentLLMProvider llmProvider;
    private final MemoryServiceClient memoryClient;
    private final ObjectMapper objectMapper;
    private final String workspaceDir;
    private final String parentSessionId;
    private final SessionKey parentSessionKey;
    private final String parentUserId;
    private final Map<String, Object> metadata;

    public SubagentLoop(String taskId, String task, ToolRegistry toolRegistry, AgentLLMProvider llmProvider,
                         MemoryServiceClient memoryClient, ObjectMapper objectMapper,
                         String workspaceDir, String parentSessionId, SessionKey parentSessionKey,
                         String parentUserId, Map<String, Object> metadata) {
        this.taskId = taskId;
        this.task = task;
        this.toolRegistry = toolRegistry;
        this.llmProvider = llmProvider;
        this.memoryClient = memoryClient;
        this.objectMapper = objectMapper;
        this.workspaceDir = workspaceDir;
        this.parentSessionId = parentSessionId;
        this.parentSessionKey = parentSessionKey;
        this.parentUserId = parentUserId == null || parentUserId.isBlank() ? "subagent" : parentUserId;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public String taskId() { return taskId; }

    /**
     * 执行子 Agent 任务（同步阻塞，应在 Virtual Thread 中调用）。
     *
     * @return 子 Agent 执行结果
     */
    public SubagentResult run() {
        log.info("[Subagent {}] 开始执行任务: {}", taskId, task);

        try {
            // 搜索经验记忆
            String expContext = "";
            try {
                expContext = memoryClient.search("类似任务的经验: " + task, 3);
            } catch (Exception e) {
                log.debug("[Subagent {}] 经验记忆检索失败: {}", taskId, e.getMessage());
            }

            // 构建初始消息
            var messages = new ArrayList<Message>();
            messages.add(Message.system(buildSubagentSystemPrompt()));
            if (!expContext.isBlank() && !expContext.equals("{}")) {
                messages.add(Message.system("<experience_memory>\n" + expContext + "\n</experience_memory>"));
            }
            messages.add(Message.user(task));

            // 使用受限工具集：子 Agent 不能再派生子 Agent。
            var restrictedTools = toolRegistry.listToolsExcept("spawn");

            // 子 Agent 循环
            int iteration = 0;
            while (iteration < MAX_ITERATIONS) {
                iteration++;
                log.debug("[Subagent {}] 第 {}/{} 轮", taskId, iteration, MAX_ITERATIONS);

                ChatResponse response = llmProvider.chat(messages, restrictedTools);

                if (response.hasToolCalls()) {
                    messages.add(Message.assistant(response.toolCalls(), response.reasoningContent()));

                    for (var tc : response.toolCalls()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> args = objectMapper.readValue(tc.arguments(), Map.class);
                            var ctx = new ToolContext(parentSessionId + "/sub/" + taskId,
                                    parentSessionKey, workspaceDir, parentUserId, metadata);
                            String result = toolRegistry.executeTool(tc.name(), args, ctx);
                            messages.add(Message.tool(tc.id(), result));
                        } catch (Exception e) {
                            messages.add(Message.tool(tc.id(), "执行失败: " + e.getMessage()));
                        }
                    }

                    messages.add(Message.system("继续完成任务。如果已完成，请直接返回结果。"));
                    continue;
                }

                // 完成
                String result = response.content() != null ? response.content() : "（无结果）";
                log.info("[Subagent {}] 完成，共 {} 轮迭代", taskId, iteration);
                return SubagentResult.completed(taskId, result);
            }

            return SubagentResult.failed(taskId, "达到最大迭代次数 " + MAX_ITERATIONS);

        } catch (Exception e) {
            log.error("[Subagent {}] 执行异常", taskId, e);
            return SubagentResult.failed(taskId, e.getMessage());
        }
    }

    private String buildSubagentSystemPrompt() {
        return """
            你是一个子任务执行器，专注于完成分配给你的具体任务。

            ## 规则

            1. **聚焦任务**: 只做任务要求的事，不要额外发挥。
            2. **高效执行**: 优先使用工具完成任务，不要无谓对话。
            3. **直接返回**: 任务完成后直接返回结果，不需要总结反思。
            4. **诚实**: 如果无法完成，说明原因。
            """
                + worktreePrompt();
    }

    private String worktreePrompt() {
        Object worktreePath = metadata.get("worktree_path");
        if (worktreePath == null || String.valueOf(worktreePath).isBlank()) {
            return "";
        }
        return """

            ## Worktree

            当前子任务已绑定独立 Git worktree。所有文件工具、exec 和 git 工具都会在该隔离目录中执行。
            不要切回主 workspace 修改代码；任务完成后返回改动摘要和验证结果。
            """;
    }
}
