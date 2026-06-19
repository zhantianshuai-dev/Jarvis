package com.zhan.jarvis.agent;

import com.zhan.jarvis.config.JarvisConfig;
import com.zhan.jarvis.bus.InboundMessage;
import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.hook.HookContext;
import com.zhan.jarvis.hook.HookManager;
import com.zhan.jarvis.llm.*;
import com.zhan.jarvis.server.sse.SseEventTypes;
import com.zhan.jarvis.session.SessionManager;
import com.zhan.jarvis.tool.ToolContext;
import com.zhan.jarvis.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;

/**
 * Agent 核心循环 — LLM ↔ Tool 往返直到任务完成或达到最大迭代次数。
 * <p>
 * 对标 Python VikingBot AgentLoop._run_agent_loop()。
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final JarvisConfig.AgentConfig agentConfig;
    private final AgentLLMProvider llmProvider;
    private final ToolRegistry toolRegistry;
    private final ContextBuilder contextBuilder;
    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper;
    private final HookManager hookManager;

    public AgentLoop(JarvisConfig.AgentConfig agentConfig, AgentLLMProvider llmProvider,
                      ToolRegistry toolRegistry, ContextBuilder contextBuilder,
                      SessionManager sessionManager, ObjectMapper objectMapper) {
        this(agentConfig, llmProvider, toolRegistry, contextBuilder, sessionManager, objectMapper, null);
    }

    public AgentLoop(JarvisConfig.AgentConfig agentConfig, AgentLLMProvider llmProvider,
                      ToolRegistry toolRegistry, ContextBuilder contextBuilder,
                      SessionManager sessionManager, ObjectMapper objectMapper,
                      HookManager hookManager) {
        this.agentConfig = agentConfig;
        this.llmProvider = llmProvider;
        this.toolRegistry = toolRegistry;
        this.contextBuilder = contextBuilder;
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
        this.hookManager = hookManager;
    }

    /**
     * 处理一条用户消息，返回最终回复。
     *
     * @param sessionId 会话 ID
     * @param userMessage 用户消息文本
     * @param userId 用户标识
     * @return 最终回复文本
     */
    public String run(String sessionId, String userMessage, String userId) {
        return run(new SessionKey("http", "default", sessionId), sessionId, userMessage, userId);
    }

    public String run(InboundMessage message) {
        return runInternal(message.sessionKey(), message.sessionId(), message.content(),
                message.userId(), message.metadata());
    }

    public String run(SessionKey sessionKey, String sessionId, String userMessage, String userId) {
        return runInternal(sessionKey, sessionId, userMessage, userId, Map.of());
    }

    private String runInternal(SessionKey sessionKey, String sessionId, String userMessage,
                               String userId, Map<String, Object> metadata) {
        var session = sessionManager.getOrCreate(sessionId, userId);
        //这里触发了agent事件
        triggerHook(HookManager.AGENT_PRE_PROCESS, sessionId, userId, Map.of(
                "message", userMessage,
                "workspace", agentConfig.workspace()
        ));

        // 构建初始消息列表，list
        var messages = contextBuilder.build(session, userMessage, 20);
        sessionManager.addMessage(sessionId, "user", userMessage, Map.of(
                "source", "Jarvis",
                "user_id", userId != null ? userId : ""
        ));

        // Agent 循环
        int iteration = 0;
        while (iteration < agentConfig.maxIterations()) {
            iteration++;
            log.info("[AgentLoop] 第 {}/{} 轮迭代", iteration, agentConfig.maxIterations());

            //tool中包含inputSchema，也就是输入格式
            var tools = toolRegistry.listTools();
            // 1. 调用 LLM
            ChatResponse response = llmProvider.chat(messages, tools);
            log.info("[AgentLoop] LLM 响应: finishReason={}, content长度={}, toolCalls={}",
                    response.finishReason(),
                    response.content() != null ? response.content().length() : 0,
                    response.hasToolCalls() ? response.toolCalls().size() : 0);

            // 2. 有工具调用 → 执行工具
            if (response.hasToolCalls()) {
                // 打印工具调用信息
                for (var tc : response.toolCalls()) {
                    log.info("[AgentLoop] 第{}轮工具调用: {}({})", iteration, tc.name(), tc.arguments());
                }

                // 记录 assistant 的工具调用消息
                messages.add(Message.assistant(response.toolCalls(), response.reasoningContent()));
                //调用memory-service去存储对话历史
                sessionManager.addMessage(sessionId, "assistant",
                        response.reasoningContent() != null ? response.reasoningContent() : "",
                        toolCallsMetadata(iteration, response.toolCalls()));

                // 并行执行所有工具
                var toolResults = executeToolsInParallel(response.toolCalls(), sessionKey, sessionId,
                        userId, metadata);

                // 打印工具执行结果
                for (int i = 0; i < toolResults.size(); i++) {
                    var tr = toolResults.get(i);
                    String preview = tr.result.length() > 200 ? tr.result.substring(0, 200) + "..." : tr.result;
                    log.info("[AgentLoop] 工具结果 {}: {}", tr.toolCallId, preview);
                }

                // 添加工具结果消息
                for (var result : toolResults) {
                    messages.add(Message.tool(result.toolCallId, result.result));
                    sessionManager.addMessage(sessionId, "tool", result.result,
                            toolResultMetadata(iteration, result));
                }

                String confirmationReply = pendingConfirmationReply(toolResults);
                if (confirmationReply != null) {
                    sessionManager.addMessage(sessionId, "assistant", confirmationReply, Map.of(
                            "source", "Jarvis",
                            "final", true,
                            "iteration", iteration,
                            "requires_confirmation", true
                    ));
                    triggerHook(HookManager.AGENT_POST_PROCESS, sessionId, userId, Map.of(
                            "reply", confirmationReply,
                            "iteration", iteration,
                            "finish_reason", "requires_confirmation",
                            "max_iterations_reached", false
                    ));
                    return confirmationReply;
                }

                // 注入反思提示
                messages.add(Message.system(
                        "请反思以上工具执行结果。如果任务已完成，请直接回复用户；如果还需要更多操作，继续调用工具。"
                ));

                log.info("[AgentLoop] 执行了 {} 个工具调用，继续循环", toolResults.size());
                continue;
            }

            // 3. 无工具调用 → 最终回复
            String finalReply = response.content() != null ? response.content() : "（无回复内容）";

            // 保存到 memory-service JSONL
            sessionManager.addMessage(sessionId, "assistant", finalReply, Map.of(
                    "source", "Jarvis",
                    "final", true,
                    "iteration", iteration,
                    "finish_reason", response.finishReason() != null ? response.finishReason() : ""
            ));
            //这里也触发了agent执行后的事件
            triggerHook(HookManager.AGENT_POST_PROCESS, sessionId, userId, Map.of(
                    "reply", finalReply,
                    "iteration", iteration,
                    "finish_reason", response.finishReason() != null ? response.finishReason() : "",
                    "max_iterations_reached", false
            ));

            log.info("[AgentLoop] 任务完成，共 {} 轮迭代", iteration);
            return finalReply;
        }

        // 达到最大迭代次数
        String fallback = "达到最大迭代次数（" + agentConfig.maxIterations() + "），请简化任务后重试。";
        sessionManager.addMessage(sessionId, "assistant", fallback, Map.of(
                "source", "Jarvis",
                "final", true,
                "max_iterations_reached", true
        ));
        //达到最大迭代次数也触发
        triggerHook(HookManager.AGENT_POST_PROCESS, sessionId, userId, Map.of(
                "reply", fallback,
                "iteration", agentConfig.maxIterations(),
                "finish_reason", "max_iterations",
                "max_iterations_reached", true
        ));
        return fallback;
    }

    public Flux<Map<String, Object>> runStreaming(SessionKey sessionKey, String sessionId,
                                                  String userMessage, String userId) {
        return Flux.create(sink -> Thread.startVirtualThread(() ->
                runStreamingInternal(sessionKey, sessionId, userMessage, userId, sink)));
    }

    private void runStreamingInternal(SessionKey sessionKey, String sessionId, String userMessage,
                                      String userId, FluxSink<Map<String, Object>> sink) {
        try {
            var session = sessionManager.getOrCreate(sessionId, userId);
            emit(sink, SseEventTypes.CONNECTED, sessionId, "", "chat", Map.of());
            triggerHook(HookManager.AGENT_PRE_PROCESS, sessionId, userId, Map.of(
                    "message", userMessage,
                    "workspace", agentConfig.workspace(),
                    "stream", true
            ));

            var messages = contextBuilder.build(session, userMessage, 20);
            sessionManager.addMessage(sessionId, "user", userMessage, Map.of(
                    "source", "Jarvis",
                    "user_id", userId != null ? userId : "",
                    "stream", true
            ));

            int iteration = 0;
            while (iteration < agentConfig.maxIterations()) {
                iteration++;
                final int currentIteration = iteration;
                var tools = toolRegistry.listTools();
                var content = new StringBuilder();
                var reasoning = new StringBuilder();
                var toolBuilders = new java.util.TreeMap<Integer, ToolCallBuilder>();
                var finishReason = new java.util.concurrent.atomic.AtomicReference<String>();

                llmProvider.streamChat(messages, tools)
                        .doOnNext(delta -> {
                            if (delta.done()) {
                                return;
                            }
                            if (delta.content() != null && !delta.content().isEmpty()) {
                                content.append(delta.content());
                                emit(sink, SseEventTypes.TOKEN, sessionId, delta.content(), "chat",
                                        Map.of("iteration", currentIteration));
                            }
                            if (delta.reasoningContent() != null && !delta.reasoningContent().isEmpty()) {
                                reasoning.append(delta.reasoningContent());
                                emit(sink, SseEventTypes.REASONING, sessionId, delta.reasoningContent(), "chat",
                                        Map.of("iteration", currentIteration));
                            }
                            for (var td : delta.toolCallDeltas()) {
                                toolBuilders.computeIfAbsent(td.index(), ignored -> new ToolCallBuilder())
                                        .append(td);
                            }
                            if (delta.finishReason() != null && !delta.finishReason().isBlank()) {
                                finishReason.set(delta.finishReason());
                            }
                        })
                        .blockLast(Duration.ofMinutes(5));

                var toolCalls = toolBuilders.values().stream()
                        .map(ToolCallBuilder::build)
                        .toList();

                if (!toolCalls.isEmpty()) {
                    messages.add(Message.assistant(toolCalls, reasoning.isEmpty() ? null : reasoning.toString()));
                    sessionManager.addMessage(sessionId, "assistant",
                            reasoning.isEmpty() ? "" : reasoning.toString(),
                            toolCallsMetadata(iteration, toolCalls));

                    for (var tc : toolCalls) {
                        emit(sink, SseEventTypes.TOOL_CALL, sessionId, tc.name(), "chat", Map.of(
                                "iteration", iteration,
                                "tool_call_id", tc.id(),
                                "tool_name", tc.name(),
                                "arguments", tc.arguments()
                        ));
                    }

                    var toolResults = executeToolsInParallel(toolCalls, sessionKey, sessionId, userId, Map.of());
                    for (var result : toolResults) {
                        messages.add(Message.tool(result.toolCallId, result.result));
                        sessionManager.addMessage(sessionId, "tool", result.result,
                                toolResultMetadata(iteration, result));
                        emit(sink, SseEventTypes.TOOL_RESULT, sessionId, result.result, "chat", Map.of(
                                "iteration", iteration,
                                "tool_call_id", result.toolCallId,
                                "tool_name", result.toolName
                        ));
                    }
                    String confirmationReply = pendingConfirmationReply(toolResults);
                    if (confirmationReply != null) {
                        //如果不为null，说明需要确认，因此封装一下消息
                        sessionManager.addMessage(sessionId, "assistant", confirmationReply, Map.of(
                                "source", "Jarvis",
                                "final", true,
                                "stream", true,
                                "iteration", iteration,
                                "requires_confirmation", true
                        ));
                        //触发一下agent日志记录
                        triggerHook(HookManager.AGENT_POST_PROCESS, sessionId, userId, Map.of(
                                "reply", confirmationReply,
                                "iteration", iteration,
                                "finish_reason", "requires_confirmation",
                                "max_iterations_reached", false,
                                "stream", true
                        ));
                        //终止本次的SSE
                        emit(sink, SseEventTypes.DONE, sessionId, confirmationReply, "chat", Map.of(
                                "iteration", iteration,
                                "finish_reason", "requires_confirmation",
                                "requires_confirmation", true
                        ));
                        //调用complete停止HTTP连接
                        sink.complete();
                        return;
                    }
                    messages.add(Message.system(
                            "请反思以上工具执行结果。如果任务已完成，请直接回复用户；如果还需要更多操作，继续调用工具。"
                    ));
                    continue;
                }

                String finalReply = content.isEmpty() ? "（无回复内容）" : content.toString();
                sessionManager.addMessage(sessionId, "assistant", finalReply, Map.of(
                        "source", "Jarvis",
                        "final", true,
                        "stream", true,
                        "iteration", iteration,
                        "finish_reason", finishReason.get() != null ? finishReason.get() : ""
                ));
                triggerHook(HookManager.AGENT_POST_PROCESS, sessionId, userId, Map.of(
                        "reply", finalReply,
                        "iteration", iteration,
                        "finish_reason", finishReason.get() != null ? finishReason.get() : "",
                        "max_iterations_reached", false,
                        "stream", true
                ));
                emit(sink, SseEventTypes.DONE, sessionId, finalReply, "chat", Map.of(
                        "iteration", iteration,
                        "finish_reason", finishReason.get() != null ? finishReason.get() : ""
                ));
                sink.complete();
                return;
            }

            String fallback = "达到最大迭代次数（" + agentConfig.maxIterations() + "），请简化任务后重试。";
            sessionManager.addMessage(sessionId, "assistant", fallback, Map.of(
                    "source", "Jarvis",
                    "final", true,
                    "stream", true,
                    "max_iterations_reached", true
            ));
            emit(sink, SseEventTypes.ERROR, sessionId, fallback, "chat", Map.of("max_iterations_reached", true));
            sink.complete();
        } catch (Exception e) {
            log.warn("[AgentLoop] stream 执行失败: {}", e.getMessage());
            log.debug("[AgentLoop] stream 执行失败详情", e);
            emit(sink, SseEventTypes.ERROR, sessionId, e.getMessage(), "chat", Map.of());
            sink.complete();
        }
    }

    /**
     * 并行执行所有工具调用（Virtual Threads）。
     */
    private List<ToolResult> executeToolsInParallel(List<ToolCall> toolCalls, SessionKey sessionKey,
                                                    String sessionId, String userId,
                                                    Map<String, Object> metadata) {
        var results = new ArrayList<ToolResult>();
        var tasks = new ArrayList<Thread>();
        // 记录每个线程对应的 tool_call_id，用于超时补结果
        var callByThread = new java.util.IdentityHashMap<Thread, ToolCall>();

        var ctx = new ToolContext(sessionId, sessionKey, agentConfig.workspace(), userId, metadata);

        for (var tc : toolCalls) {
            var thread = Thread.startVirtualThread(() -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = objectMapper.readValue(tc.arguments(), Map.class);
                    String result = toolRegistry.executeTool(tc.name(), args, ctx);
                    synchronized (results) {
                        results.add(new ToolResult(tc.id(), tc.name(), tc.arguments(), result));
                    }
                } catch (Exception e) {
                    log.error("工具执行异常: {} — {}", tc.name(), e.getMessage(), e);
                    synchronized (results) {
                        results.add(new ToolResult(tc.id(), tc.name(), tc.arguments(),
                                "工具执行异常: " + e.getMessage()));
                    }
                }
            });
            tasks.add(thread);
            callByThread.put(thread, tc);
        }

        // 等待所有线程完成（每个工具最长 60 秒）
        for (var t : tasks) {
            try {
                t.join(Duration.ofSeconds(120).toMillis());
                if (t.isAlive()) {
                    var tc = callByThread.get(t);
                    String callId = tc != null ? tc.id() : "";
                    log.warn("工具执行超时: callId={}", callId);
                    synchronized (results) {
                        results.add(new ToolResult(callId,
                                tc != null ? tc.name() : "",
                                tc != null ? tc.arguments() : "{}",
                                "工具执行超时（60秒）"));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待工具线程被中断");
            }
        }

        return results;
    }

    private String pendingConfirmationReply(List<ToolResult> toolResults) {
        for (var result : toolResults) {
            try {
                JsonNode root = objectMapper.readTree(result.result);
                if (root != null && root.has("requires_confirmation")
                        && root.get("requires_confirmation").asBoolean(false)) {
                    String tool = root.path("tool").asText(result.toolName);
                    String action = root.path("action").asText("");
                    String summary = root.path("summary").asText("");
                    String confirmId = root.path("confirm_id").asText("");
                    String message = root.path("message").asText("该 Git 操作需要人工确认。");
                    String command = root.has("command") ? root.path("command").toString() : "";
                    String expiresAt = root.path("expires_at").asText("");
                    String endpoint = root.path("confirm_endpoint").asText("/api/v1/tools/confirm");
                    return """
                            工具操作需要人工确认，已暂停自动执行。

                            工具: %s
                            操作: %s
                            摘要: %s
                            命令: %s
                            confirm_id: %s
                            过期时间: %s

                            %s
                            确认后由后端调用 POST %s 执行，LLM 不能自行确认。
                            """.formatted(tool, action, summary, command, confirmId, expiresAt, message, endpoint).strip();
                }
            } catch (Exception ignored) {
                // 非 JSON 工具结果不参与确认判断。
            }
        }
        return null;
    }

    private Map<String, Object> toolCallsMetadata(int iteration, List<ToolCall> toolCalls) {
        var metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("source", "Jarvis");
        metadata.put("trace", true);
        metadata.put("trace_type", "assistant_tool_calls");
        metadata.put("iteration", iteration);
        metadata.put("tool_calls", toolCalls.stream()
                .map(tc -> {
                    var item = new java.util.LinkedHashMap<String, Object>();
                    item.put("id", tc.id());
                    item.put("name", tc.name());
                    item.put("arguments", tc.arguments());
                    return item;
                })
                .toList());
        return metadata;
    }

    private Map<String, Object> toolResultMetadata(int iteration, ToolResult result) {
        var metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("source", "Jarvis");
        metadata.put("trace", true);
        metadata.put("trace_type", "tool_result");
        metadata.put("iteration", iteration);
        metadata.put("tool_call_id", result.toolCallId);
        metadata.put("tool_name", result.toolName);
        metadata.put("arguments", result.arguments);
        return metadata;
    }

    private void triggerHook(String eventType, String sessionId, String userId, Map<String, Object> payload) {
        if (hookManager == null) {
            return;
        }
        hookManager.trigger(HookContext.of(eventType, sessionId, userId, payload));
    }

    private void emit(FluxSink<Map<String, Object>> sink, String type, String sessionId, String content,
                      String source, Map<String, Object> extra) {
        if (sink.isCancelled()) {
            return;
        }
        var event = new LinkedHashMap<String, Object>();
        event.put("type", type);
        event.put("session_id", sessionId);
        event.put("content", content == null ? "" : content);
        event.put("source", source == null ? "" : source);
        event.put("created_at", Instant.now().toString());
        if (extra != null && !extra.isEmpty()) {
            event.putAll(extra);
        }
        sink.next(event);
    }

    /** 工具执行结果 */
    private record ToolResult(String toolCallId, String toolName, String arguments, String result) {}

    private static class ToolCallBuilder {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        void append(ChatStreamDelta.ToolCallDelta delta) {
            if (delta.id() != null && !delta.id().isBlank()) {
                id = delta.id();
            }
            if (delta.name() != null) {
                name.append(delta.name());
            }
            if (delta.arguments() != null) {
                arguments.append(delta.arguments());
            }
        }

        ToolCall build() {
            String safeId = id != null && !id.isBlank()
                    ? id
                    : "call_" + cn.hutool.core.util.IdUtil.getSnowflake(1, 1).nextId();
            return new ToolCall(safeId, name.toString(), arguments.toString());
        }
    }
}
