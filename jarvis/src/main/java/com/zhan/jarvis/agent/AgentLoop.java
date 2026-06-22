package com.zhan.jarvis.agent;

import com.zhan.jarvis.bus.InboundMessage;
import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.config.JarvisConfig;
import com.zhan.jarvis.hook.HookContext;
import com.zhan.jarvis.hook.HookManager;
import com.zhan.jarvis.agent.loop.LoopObserver;
import com.zhan.jarvis.agent.loop.LoopOutcome;
import com.zhan.jarvis.agent.loop.LoopState;
import com.zhan.jarvis.agent.loop.PendingConfirmation;
import com.zhan.jarvis.agent.loop.SseLoopObserver;
import com.zhan.jarvis.agent.loop.ToolCallBuilder;
import com.zhan.jarvis.agent.loop.ToolResult;
import com.zhan.jarvis.agent.loop.TokenUsageAccumulator;
import com.zhan.jarvis.llm.AgentLLMProvider;
import com.zhan.jarvis.llm.ChatResponse;
import com.zhan.jarvis.llm.Message;
import com.zhan.jarvis.llm.ToolCall;
import com.zhan.jarvis.permission.AgentCheckpoint;
import com.zhan.jarvis.permission.AgentCheckpointStore;
import com.zhan.jarvis.permission.PendingToolPermission;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 核心循环 — LLM ↔ Tool 往返直到任务完成或达到最大迭代次数。
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
    private final AgentCheckpointStore checkpointStore;

    public AgentLoop(JarvisConfig.AgentConfig agentConfig, AgentLLMProvider llmProvider,
                     ToolRegistry toolRegistry, ContextBuilder contextBuilder,
                     SessionManager sessionManager, ObjectMapper objectMapper) {
        this(agentConfig, llmProvider, toolRegistry, contextBuilder, sessionManager, objectMapper, null, null);
    }

    public AgentLoop(JarvisConfig.AgentConfig agentConfig, AgentLLMProvider llmProvider,
                     ToolRegistry toolRegistry, ContextBuilder contextBuilder,
                     SessionManager sessionManager, ObjectMapper objectMapper,
                     HookManager hookManager) {
        this(agentConfig, llmProvider, toolRegistry, contextBuilder, sessionManager, objectMapper,
                hookManager, null);
    }

    public AgentLoop(JarvisConfig.AgentConfig agentConfig, AgentLLMProvider llmProvider,
                     ToolRegistry toolRegistry, ContextBuilder contextBuilder,
                     SessionManager sessionManager, ObjectMapper objectMapper,
                     HookManager hookManager, AgentCheckpointStore checkpointStore) {
        this.agentConfig = agentConfig;
        this.llmProvider = llmProvider;
        this.toolRegistry = toolRegistry;
        this.contextBuilder = contextBuilder;
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
        this.hookManager = hookManager;
        this.checkpointStore = checkpointStore;
    }

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

    /**
     * 人工确认工具执行后恢复原中断点。
     * 如果 checkpoint 已过期，则退回到一条 continuation message，保证用户能看到工具执行结果。
     */
    public String continueAfterToolConfirmation(PendingToolPermission pending, String toolResult,
                                                String confirmedBy) {
        String sessionId = hasText(pending.sessionId()) ? pending.sessionId() : "tool-confirm:" + pending.confirmId();
        String userId = hasText(pending.requestedBy()) ? pending.requestedBy() : confirmedBy;
        SessionKey sessionKey = pending.sessionKey() != null
                ? pending.sessionKey()
                : new SessionKey("http", "tool-confirm", sessionId);

        sessionManager.addMessage(sessionId, "tool", toolResult, Map.of(
                "source", "Jarvis",
                "trace", true,
                "trace_type", "confirmed_tool_result",
                "confirm_id", pending.confirmId(),
                "tool_name", pending.toolName(),
                "arguments", pending.arguments(),
                "confirmed_by", confirmedBy != null ? confirmedBy : ""
        ));

        //这个位置去取checkpoint
        AgentCheckpoint checkpoint = checkpointStore != null
                ? checkpointStore.take(pending.confirmId()).orElse(null)
                : null;
        if (checkpoint != null) {
            return resumeFromCheckpoint(checkpoint, toolResult, confirmedBy).reply();
        }

        var metadata = new LinkedHashMap<String, Object>(pending.metadata() == null ? Map.of() : pending.metadata());
        metadata.put("confirmed_by", confirmedBy != null ? confirmedBy : "");
        metadata.put("confirm_id", pending.confirmId());
        metadata.put("confirmed_tool_name", pending.toolName());

        String continuationMessage = """
                用户已经人工确认并执行了此前暂停的工具操作。

                工具: %s
                摘要: %s
                confirm_id: %s

                工具执行结果:
                %s

                请基于这个已确认的工具执行结果继续完成用户原本的任务。
                如果任务已经完成，请直接给用户一个清晰的结果总结；如果还需要其他操作，可以继续调用工具。
                """.formatted(
                pending.toolName(),
                pending.summary() != null ? pending.summary() : "",
                pending.confirmId(),
                toolResult != null ? toolResult : ""
        ).strip();

        triggerHook(HookManager.AGENT_PRE_PROCESS, sessionId, userId, Map.of(
                "message", continuationMessage,
                "workspace", agentConfig.workspace(),
                "continuation", true
        ));
        var session = sessionManager.getOrCreate(sessionId, userId);
        var messages = contextBuilder.build(session, continuationMessage, 20);
        return runLoop(new LoopState(sessionKey, sessionId, userId, metadata, messages, 0, false,
                Map.of("continuation", true), new TokenUsageAccumulator()), LoopObserver.NOOP).reply();
    }

    private String runInternal(SessionKey sessionKey, String sessionId, String userMessage,
                               String userId, Map<String, Object> metadata) {
        var session = sessionManager.getOrCreate(sessionId, userId);
        triggerHook(HookManager.AGENT_PRE_PROCESS, sessionId, userId, Map.of(
                "message", userMessage,
                "workspace", agentConfig.workspace()
        ));

        var messages = contextBuilder.build(session, userMessage, 20);
        sessionManager.addMessage(sessionId, "user", userMessage, Map.of(
                "source", "Jarvis",
                "user_id", userId != null ? userId : ""
        ));

        return runLoop(new LoopState(sessionKey, sessionId, userId,
                metadata == null ? Map.of() : metadata, messages, 0, false, Map.of(),
                new TokenUsageAccumulator()), LoopObserver.NOOP).reply();
    }

    public Flux<Map<String, Object>> runStreaming(SessionKey sessionKey, String sessionId,
                                                  String userMessage, String userId) {
        return Flux.create(sink -> Thread.startVirtualThread(() ->
                runStreamingInternal(sessionKey, sessionId, userMessage, userId, sink)));
    }

    private void runStreamingInternal(SessionKey sessionKey, String sessionId, String userMessage,
                                      String userId, FluxSink<Map<String, Object>> sink) {
        try {
            var observer = new SseLoopObserver(sink);
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

            runLoop(new LoopState(sessionKey, sessionId, userId, Map.of(), messages, 0, true,
                    Map.of("stream", true), new TokenUsageAccumulator()), observer);
            sink.complete();
        } catch (Exception e) {
            log.warn("[AgentLoop] stream 执行失败: {}", e.getMessage());
            log.debug("[AgentLoop] stream 执行失败详情", e);
            emit(sink, SseEventTypes.ERROR, sessionId, e.getMessage(), "chat", Map.of());
            sink.complete();
        }
    }

    private LoopOutcome resumeFromCheckpoint(AgentCheckpoint checkpoint, String confirmedToolResult,
                                             String confirmedBy) {
        String userId = hasText(checkpoint.userId()) ? checkpoint.userId() : confirmedBy;
        var messages = new ArrayList<>(checkpoint.messages());
        messages.add(Message.tool(checkpoint.pendingToolCallId(), confirmedToolResult));
        messages.add(Message.system(
                "用户已经人工确认并执行了暂停的工具操作。请反思以上工具执行结果。"
                        + "如果任务已完成，请直接回复用户；如果还需要更多操作，继续调用工具。"
        ));
        return runLoop(new LoopState(
                checkpoint.sessionKey(),
                checkpoint.sessionId(),
                userId,
                checkpoint.metadata() == null ? Map.of() : checkpoint.metadata(),
                messages,
                checkpoint.iteration(),
                false,
                Map.of("resumed_from_checkpoint", true),
                new TokenUsageAccumulator()
        ), LoopObserver.NOOP);
    }

    /**
     * 唯一的 Agent 循环实现。
     * 普通 HTTP、SSE、人工确认恢复都通过不同 LoopState/Observer 复用这里。
     */
    private LoopOutcome runLoop(LoopState state, LoopObserver observer) {
        int iteration = state.startIteration();
        while (iteration < agentConfig.maxIterations()) {
            iteration++;
            log.info("[AgentLoop] 第 {}/{} 轮迭代: sessionId={}, stream={}",
                    iteration, agentConfig.maxIterations(), state.sessionId(), state.stream());

            var tools = toolRegistry.listTools();
            ChatResponse response = state.stream()
                    ? streamChatResponse(state, tools, observer, iteration)
                    : llmProvider.chat(state.messages(), tools);
            state.tokenUsage().add(response.usage());
            log.info("[AgentLoop] LLM 响应: finishReason={}, content长度={}, toolCalls={}",
                    response.finishReason(),
                    response.content() != null ? response.content().length() : 0,
                    response.hasToolCalls() ? response.toolCalls().size() : 0);

            if (response.hasToolCalls()) {
                for (var tc : response.toolCalls()) {
                    log.info("[AgentLoop] 第{}轮工具调用: {}({})", iteration, tc.name(), tc.arguments());
                    observer.onToolCall(state, iteration, tc);
                }
                //当前工具结果加入到当前上下文
                state.messages().add(Message.assistant(response.toolCalls(), response.reasoningContent()));
                //发送到memory-service中进行持久化，然后自动生成摘要，提取记忆
                sessionManager.addMessage(state.sessionId(), "assistant",
                        response.reasoningContent() != null ? response.reasoningContent() : "",
                        toolCallsMetadata(iteration, response.toolCalls()));

                var toolResults = executeToolsInParallel(response.toolCalls(), state.sessionKey(),
                        state.sessionId(), state.userId(), state.metadata());
                logToolResults(toolResults);

                PendingConfirmation pending = findPendingConfirmation(toolResults);
                if (pending != null) {
                    addToolResults(state, iteration, toolResults, observer, pending);
                    saveCheckpoint(pending, state, iteration);
                    var outcome = finishRequiresConfirmation(state, iteration, pending.reply());
                    observer.onDone(outcome);
                    return outcome;
                }

                //如果不需人工确认
                addToolResults(state, iteration, toolResults, observer, null);
                state.messages().add(Message.system(
                        "请反思以上工具执行结果。如果任务已完成，请直接回复用户；如果还需要更多操作，继续调用工具。"
                ));
                continue;
            }

            String finalReply = response.content() != null ? response.content() : "（无回复内容）";
            var outcome = finishFinal(state, iteration, finalReply,
                    response.finishReason() != null ? response.finishReason() : "");
            observer.onDone(outcome);
            return outcome;
        }

        String fallback = "达到最大迭代次数（" + agentConfig.maxIterations() + "），请简化任务后重试。";
        var outcome = finishMaxIterations(state, fallback);
        observer.onDone(outcome);
        return outcome;
    }

    private ChatResponse streamChatResponse(LoopState state, List<?> tools, LoopObserver observer, int iteration) {
        var content = new StringBuilder();
        var reasoning = new StringBuilder();
        var toolBuilders = new TreeMap<Integer, ToolCallBuilder>();
        var finishReason = new AtomicReference<String>();
        var usage = new AtomicReference<>(new ChatResponse.TokenUsage(0, 0, 0));

        @SuppressWarnings("unchecked")
        var typedTools = (List<com.zhan.jarvis.llm.ToolDefinition>) tools;
        llmProvider.streamChat(state.messages(), typedTools)
                .doOnNext(delta -> {
                    if (delta.done()) {
                        return;
                    }
                    if (delta.content() != null && !delta.content().isEmpty()) {
                        content.append(delta.content());
                        observer.onToken(state, iteration, delta.content());
                    }
                    if (delta.reasoningContent() != null && !delta.reasoningContent().isEmpty()) {
                        reasoning.append(delta.reasoningContent());
                        observer.onReasoning(state, iteration, delta.reasoningContent());
                    }
                    for (var td : delta.toolCallDeltas()) {
                        toolBuilders.computeIfAbsent(td.index(), ignored -> new ToolCallBuilder()).append(td);
                    }
                    if (delta.usage() != null && delta.usage().totalTokens() > 0) {
                        usage.set(delta.usage());
                    }
                    if (delta.finishReason() != null && !delta.finishReason().isBlank()) {
                        finishReason.set(delta.finishReason());
                    }
                })
                .blockLast(Duration.ofMinutes(5));

        var toolCalls = toolBuilders.values().stream()
                .map(ToolCallBuilder::build)
                .toList();
        return new ChatResponse(
                content.toString(),
                toolCalls,
                finishReason.get() != null ? finishReason.get() : "",
                usage.get(),
                reasoning.isEmpty() ? null : reasoning.toString()
        );
    }

    private LoopOutcome finishRequiresConfirmation(LoopState state, int iteration, String reply) {
        var metadata = withTokenUsage(state, mergedMeta(state, Map.of(
                "source", "Jarvis",
                "final", true,
                "iteration", iteration,
                "requires_confirmation", true
        )));
        sessionManager.addMessage(state.sessionId(), "assistant", reply, metadata);
        triggerHook(HookManager.AGENT_POST_PROCESS, state.sessionId(), state.userId(), mergedMeta(state, Map.of(
                "reply", reply,
                "iteration", iteration,
                "finish_reason", "requires_confirmation",
                "max_iterations_reached", false
        )));
        return new LoopOutcome(state.sessionId(), reply, iteration, "requires_confirmation", true, false,
                state.tokenUsage().toMap());
    }

    private LoopOutcome finishFinal(LoopState state, int iteration, String reply, String finishReason) {
        sessionManager.addMessage(state.sessionId(), "assistant", reply, withTokenUsage(state, mergedMeta(state, Map.of(
                "source", "Jarvis",
                "final", true,
                "iteration", iteration,
                "finish_reason", finishReason
        ))));
        triggerHook(HookManager.AGENT_POST_PROCESS, state.sessionId(), state.userId(), mergedMeta(state, Map.of(
                "reply", reply,
                "iteration", iteration,
                "finish_reason", finishReason,
                "max_iterations_reached", false
        )));
        log.info("[AgentLoop] 任务完成，共 {} 轮迭代", iteration);
        return new LoopOutcome(state.sessionId(), reply, iteration, finishReason, false, false,
                state.tokenUsage().toMap());
    }

    private LoopOutcome finishMaxIterations(LoopState state, String reply) {
        sessionManager.addMessage(state.sessionId(), "assistant", reply, withTokenUsage(state, mergedMeta(state, Map.of(
                "source", "Jarvis",
                "final", true,
                "max_iterations_reached", true
        ))));
        triggerHook(HookManager.AGENT_POST_PROCESS, state.sessionId(), state.userId(), mergedMeta(state, Map.of(
                "reply", reply,
                "iteration", agentConfig.maxIterations(),
                "finish_reason", "max_iterations",
                "max_iterations_reached", true
        )));
        return new LoopOutcome(state.sessionId(), reply, agentConfig.maxIterations(), "max_iterations", false, true,
                state.tokenUsage().toMap());
    }

    private void addToolResults(LoopState state, int iteration, List<ToolResult> toolResults,
                                LoopObserver observer, PendingConfirmation pending) {
        for (var result : toolResults) {
            if (pending != null && Objects.equals(result.toolCallId(), pending.result().toolCallId())) {
                continue;
            }
            state.messages().add(Message.tool(result.toolCallId(), result.result()));
            sessionManager.addMessage(state.sessionId(), "tool", result.result(),
                    toolResultMetadata(iteration, result));
            observer.onToolResult(state, iteration, result);
        }
    }

    private void saveCheckpoint(PendingConfirmation pending, LoopState state, int iteration) {
        if (checkpointStore == null || !hasText(pending.confirmId())) {
            return;
        }
        //存入concurrentHashMap，key：confirmId， value：AgentCheckpoint
        checkpointStore.put(new AgentCheckpoint(
                pending.confirmId(),
                pending.result().toolCallId(),
                state.sessionKey(),
                state.sessionId(),
                state.userId(),
                List.copyOf(new ArrayList<>(state.messages())),
                iteration,
                Map.copyOf(state.metadata() == null ? Map.of() : state.metadata()),
                pending.expiresAt()
        ));
    }

    private List<ToolResult> executeToolsInParallel(List<ToolCall> toolCalls, SessionKey sessionKey,
                                                    String sessionId, String userId,
                                                    Map<String, Object> metadata) {
        var results = new ArrayList<ToolResult>();
        var tasks = new ArrayList<Thread>();
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

        for (var t : tasks) {
            try {
                t.join(Duration.ofSeconds(120).toMillis());
                if (t.isAlive()) {
                    var tc = callByThread.get(t);
                    log.warn("工具执行超时: callId={}", tc != null ? tc.id() : "");
                    synchronized (results) {
                        results.add(new ToolResult(
                                tc != null ? tc.id() : "",
                                tc != null ? tc.name() : "",
                                tc != null ? tc.arguments() : "{}",
                                "工具执行超时（120秒）"
                        ));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待工具线程被中断");
            }
        }
        return results;
    }

    private void logToolResults(List<ToolResult> toolResults) {
        for (var result : toolResults) {
            String preview = result.result().length() > 200
                    ? result.result().substring(0, 200) + "..."
                    : result.result();
            log.info("[AgentLoop] 工具结果 {}: {}", result.toolCallId(), preview);
        }
    }

    private PendingConfirmation findPendingConfirmation(List<ToolResult> toolResults) {
        for (var result : toolResults) {
            try {
                JsonNode root = objectMapper.readTree(result.result());
                if (root != null && root.has("requires_confirmation")
                        && root.get("requires_confirmation").asBoolean(false)) {
                    String tool = root.path("tool").asText(result.toolName());
                    String action = root.path("action").asText("");
                    String summary = root.path("summary").asText("");
                    String confirmId = root.path("confirm_id").asText("");
                    String message = root.path("message").asText("该工具操作需要人工确认。");
                    String command = root.has("command") ? root.path("command").toString() : "";
                    String expiresAt = root.path("expires_at").asText("");
                    String endpoint = root.path("confirm_endpoint").asText("/api/v1/tools/confirm");
                    String reply = """
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
                    return new PendingConfirmation(result, reply, confirmId, parseExpiresAt(expiresAt));
                }
            } catch (Exception ignored) {
                // 非 JSON 工具结果不参与确认判断。
            }
        }
        return null;
    }

    private Map<String, Object> toolCallsMetadata(int iteration, List<ToolCall> toolCalls) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("source", "Jarvis");
        metadata.put("trace", true);
        metadata.put("trace_type", "assistant_tool_calls");
        metadata.put("iteration", iteration);
        metadata.put("tool_calls", toolCalls.stream()
                .map(tc -> {
                    var item = new LinkedHashMap<String, Object>();
                    item.put("id", tc.id());
                    item.put("name", tc.name());
                    item.put("arguments", tc.arguments());
                    return item;
                })
                .toList());
        return metadata;
    }

    private Map<String, Object> toolResultMetadata(int iteration, ToolResult result) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("source", "Jarvis");
        metadata.put("trace", true);
        metadata.put("trace_type", "tool_result");
        metadata.put("iteration", iteration);
        metadata.put("tool_call_id", result.toolCallId());
        metadata.put("tool_name", result.toolName());
        metadata.put("arguments", result.arguments());
        return metadata;
    }

    private Map<String, Object> mergedMeta(LoopState state, Map<String, Object> base) {
        var metadata = new LinkedHashMap<String, Object>(base);
        if (state.outputMetadata() != null) {
            metadata.putAll(state.outputMetadata());
        }
        return metadata;
    }

    private Map<String, Object> withTokenUsage(LoopState state, Map<String, Object> base) {
        var metadata = new LinkedHashMap<String, Object>(base);
        metadata.put("token_usage", state.tokenUsage().toMap());
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

    private static Instant parseExpiresAt(String value) {
        if (hasText(value)) {
            try {
                return Instant.parse(value);
            } catch (Exception ignored) {
                // fallback below
            }
        }
        return Instant.now().plus(Duration.ofMinutes(10));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
