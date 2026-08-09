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
import com.zhan.jarvis.agent.event.RunEvent;
import com.zhan.jarvis.agent.event.RunEventStore;
import com.zhan.jarvis.agent.middleware.AgentMiddlewareChain;
import com.zhan.jarvis.agent.planner.ExecutionPlan;
import com.zhan.jarvis.agent.planner.PlanManager;
import com.zhan.jarvis.agent.planner.Planner;
import com.zhan.jarvis.artifact.ArtifactManager;
import com.zhan.jarvis.llm.AgentLLMProvider;
import com.zhan.jarvis.llm.ChatResponse;
import com.zhan.jarvis.llm.Message;
import com.zhan.jarvis.llm.ToolDefinition;
import com.zhan.jarvis.llm.ToolCall;
import com.zhan.jarvis.permission.AgentCheckpoint;
import com.zhan.jarvis.permission.AgentCheckpointStore;
import com.zhan.jarvis.permission.PendingToolPermission;
import com.zhan.jarvis.server.sse.SseEventTypes;
import com.zhan.jarvis.session.SessionManager;
import com.zhan.jarvis.todo.TodoManager;
import com.zhan.jarvis.todo.TodoStatus;
import com.zhan.jarvis.tool.ToolContext;
import com.zhan.jarvis.tool.ToolPayloadSanitizer;
import com.zhan.jarvis.tool.ToolRegistry;
import com.zhan.jarvis.workspace.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 核心循环 — LLM 与工具往返直到任务完成或达到最大迭代次数。
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final int MAX_VISIBLE_TOOL_RESULT_CHARS = 8_000;
    private static final int MAX_VISIBLE_TOOL_FILES = 30;
    private static final long TOOL_EXECUTION_TIMEOUT_SECONDS = 330;
    private static final int MAX_TODO_COMPLETION_REMINDERS = 2;
    private static final String META_TODO_COMPLETION_REMINDERS = "todo_completion_reminders";

    private final JarvisConfig.AgentConfig agentConfig;
    private final AgentLLMProvider llmProvider;
    private final ToolRegistry toolRegistry;
    private final ContextBuilder contextBuilder;
    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper;
    private final HookManager hookManager;
    private final AgentCheckpointStore checkpointStore;
    private final WorkspaceResolver workspaceResolver;
    private final ToolPayloadSanitizer toolPayloadSanitizer;
    private final AgentMiddlewareChain middlewareChain;
    private final RunEventStore runEventStore;
    private final ArtifactManager artifactManager;
    private final Planner planner;
    private final PlanManager planManager;
    private final TodoManager todoManager;

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
        this(agentConfig, llmProvider, toolRegistry, contextBuilder, sessionManager, objectMapper,
                hookManager, checkpointStore, new WorkspaceResolver(agentConfig));
    }

    public AgentLoop(JarvisConfig.AgentConfig agentConfig, AgentLLMProvider llmProvider,
                     ToolRegistry toolRegistry, ContextBuilder contextBuilder,
                     SessionManager sessionManager, ObjectMapper objectMapper,
                     HookManager hookManager, AgentCheckpointStore checkpointStore,
                     WorkspaceResolver workspaceResolver) {
        this(agentConfig, llmProvider, toolRegistry, contextBuilder, sessionManager, objectMapper,
                hookManager, checkpointStore, workspaceResolver, AgentMiddlewareChain.EMPTY, RunEventStore.NOOP, null);
    }

    public AgentLoop(JarvisConfig.AgentConfig agentConfig, AgentLLMProvider llmProvider,
                     ToolRegistry toolRegistry, ContextBuilder contextBuilder,
                     SessionManager sessionManager, ObjectMapper objectMapper,
                     HookManager hookManager, AgentCheckpointStore checkpointStore,
                     WorkspaceResolver workspaceResolver, AgentMiddlewareChain middlewareChain) {
        this(agentConfig, llmProvider, toolRegistry, contextBuilder, sessionManager, objectMapper,
                hookManager, checkpointStore, workspaceResolver, middlewareChain, RunEventStore.NOOP, null);
    }

    public AgentLoop(JarvisConfig.AgentConfig agentConfig, AgentLLMProvider llmProvider,
                     ToolRegistry toolRegistry, ContextBuilder contextBuilder,
                     SessionManager sessionManager, ObjectMapper objectMapper,
                     HookManager hookManager, AgentCheckpointStore checkpointStore,
                     WorkspaceResolver workspaceResolver, AgentMiddlewareChain middlewareChain,
                     RunEventStore runEventStore) {
        this(agentConfig, llmProvider, toolRegistry, contextBuilder, sessionManager, objectMapper,
                hookManager, checkpointStore, workspaceResolver, middlewareChain, runEventStore, null);
    }

    public AgentLoop(JarvisConfig.AgentConfig agentConfig, AgentLLMProvider llmProvider,
                     ToolRegistry toolRegistry, ContextBuilder contextBuilder,
                     SessionManager sessionManager, ObjectMapper objectMapper,
                     HookManager hookManager, AgentCheckpointStore checkpointStore,
                     WorkspaceResolver workspaceResolver, AgentMiddlewareChain middlewareChain,
                     RunEventStore runEventStore, ArtifactManager artifactManager) {
        this(agentConfig, llmProvider, toolRegistry, contextBuilder, sessionManager, objectMapper,
                hookManager, checkpointStore, workspaceResolver, middlewareChain, runEventStore, artifactManager,
                null, null, null);
    }

    public AgentLoop(JarvisConfig.AgentConfig agentConfig, AgentLLMProvider llmProvider,
                     ToolRegistry toolRegistry, ContextBuilder contextBuilder,
                     SessionManager sessionManager, ObjectMapper objectMapper,
                     HookManager hookManager, AgentCheckpointStore checkpointStore,
                     WorkspaceResolver workspaceResolver, AgentMiddlewareChain middlewareChain,
                     RunEventStore runEventStore, ArtifactManager artifactManager,
                     Planner planner, PlanManager planManager, TodoManager todoManager) {
        this.agentConfig = agentConfig;
        this.llmProvider = llmProvider;
        this.toolRegistry = toolRegistry;
        this.contextBuilder = contextBuilder;
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
        this.hookManager = hookManager;
        this.checkpointStore = checkpointStore;
        this.workspaceResolver = workspaceResolver;
        this.toolPayloadSanitizer = new ToolPayloadSanitizer(objectMapper);
        this.middlewareChain = middlewareChain == null ? AgentMiddlewareChain.EMPTY : middlewareChain;
        this.runEventStore = runEventStore == null ? RunEventStore.NOOP : runEventStore;
        this.artifactManager = artifactManager;
        this.planner = planner;
        this.planManager = planManager;
        this.todoManager = todoManager;
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
     * 如果检查点已过期，则退回到一条继续执行消息，保证用户能看到工具执行结果。
     */
    public String continueAfterToolConfirmation(PendingToolPermission pending, String toolResult,
                                                String confirmedBy) {
        String sessionId = hasText(pending.sessionId()) ? pending.sessionId() : "tool-confirm:" + pending.confirmId();
        String userId = hasText(pending.requestedBy()) ? pending.requestedBy() : confirmedBy;
        SessionKey sessionKey = pending.sessionKey() != null
                ? pending.sessionKey()
                : new SessionKey("http", "tool-confirm", sessionId);

        var confirmedMetadata = new LinkedHashMap<String, Object>();
        confirmedMetadata.put("source", "Jarvis");
        confirmedMetadata.put("trace", true);
        confirmedMetadata.put("hidden", true);
        confirmedMetadata.put("display_event", false);
        confirmedMetadata.put("trace_type", "confirmed_tool_result");
        confirmedMetadata.put("confirm_id", pending.confirmId());
        confirmedMetadata.put("tool_name", pending.toolName());
        confirmedMetadata.put("arguments", toolPayloadSanitizer.sanitizeArgumentMap(pending.toolName(), pending.arguments()));
        confirmedMetadata.put("confirmed_by", confirmedBy != null ? confirmedBy : "");
        sessionManager.addMessage(sessionId, "tool", toolResult, confirmedMetadata);

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
                "workspace", workspaceFor(metadata),
                "continuation", true
        ));
        var session = sessionManager.getOrCreate(sessionId, userId);
        var messages = contextBuilder.build(session, continuationMessage, 20,
                RunMode.from(metadata.get("mode")), workspaceFor(metadata));
        return runLoop(newState(sessionKey, sessionId, userId, metadata, messages, 0, false,
                Map.of("continuation", true)), LoopObserver.NOOP).reply();
    }

    private String runInternal(SessionKey sessionKey, String sessionId, String userMessage,
                               String userId, Map<String, Object> metadata) {
        var session = sessionManager.getOrCreate(sessionId, userId);
        var runMode = RunMode.from(metadata == null ? null : metadata.get("mode"));
        String workspace = workspaceFor(metadata);
        triggerHook(HookManager.AGENT_PRE_PROCESS, sessionId, userId, Map.of(
                "message", userMessage,
                "workspace", workspace,
                "mode", runMode.value()
        ));

        var messages = contextBuilder.build(session, userMessage, 20, runMode, workspace);
        applyVisionAttachments(messages, metadata, userMessage);
        sessionManager.addMessage(sessionId, "user", displayMessage(metadata, userMessage), Map.of(
                "source", "Jarvis",
                "user_id", userId != null ? userId : "",
                "display_event", true,
                "event_type", "user_message"
        ));

        var state = newState(sessionKey, sessionId, userId,
                withCurrentMessage(metadata, userMessage), messages, 0, false, Map.of());
        prepareExecutionPlan(state, userMessage, LoopObserver.NOOP);
        return runLoop(state, LoopObserver.NOOP).reply();
    }

    public Flux<Map<String, Object>> runStreaming(SessionKey sessionKey, String sessionId,
                                                  String userMessage, String userId) {
        return runStreaming(sessionKey, sessionId, userMessage, userId, Map.of());
    }

    public Flux<Map<String, Object>> runStreaming(SessionKey sessionKey, String sessionId,
                                                  String userMessage, String userId,
                                                  Map<String, Object> metadata) {
        return Flux.create(sink -> Thread.startVirtualThread(() ->
                runStreamingInternal(sessionKey, sessionId, userMessage, userId, metadata, sink)));
    }

    private void runStreamingInternal(SessionKey sessionKey, String sessionId, String userMessage,
                                      String userId, Map<String, Object> metadata,
                                      FluxSink<Map<String, Object>> sink) {
        try {
            //构建SSEObserver
            var observer = new SseLoopObserver(sink);
            var session = sessionManager.getOrCreate(sessionId, userId);
            var runMode = RunMode.from(metadata == null ? null : metadata.get("mode"));
            String workspace = workspaceFor(metadata);
            emit(sink, SseEventTypes.CONNECTED, sessionId, "", "chat", Map.of());
            triggerHook(HookManager.AGENT_PRE_PROCESS, sessionId, userId, Map.of(
                    "message", userMessage,
                    "workspace", workspace,
                    "mode", runMode.value(),
                    "stream", true
            ));

            var messages = contextBuilder.build(session, userMessage, 20, runMode, workspace);
            applyVisionAttachments(messages, metadata, userMessage);
            sessionManager.addMessage(sessionId, "user", displayMessage(metadata, userMessage), Map.of(
                    "source", "Jarvis",
                    "user_id", userId != null ? userId : "",
                    "stream", true,
                    "display_event", true,
                    "event_type", "user_message"
            ));

            var state = newState(sessionKey, sessionId, userId, withCurrentMessage(metadata, userMessage), messages, 0, true,
                    Map.of("stream", true));
            prepareExecutionPlan(state, userMessage, observer);
            runLoop(state, observer);
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
                checkpoint.runId(),
                checkpoint.sessionKey(),
                checkpoint.sessionId(),
                userId,
                checkpoint.metadata() == null ? Map.of() : checkpoint.metadata(),
                messages,
                checkpoint.iteration(),
                false,
                checkpoint.outputMetadata() == null ? Map.of("resumed_from_checkpoint", true) : checkpoint.outputMetadata(),
                TokenUsageAccumulator.fromMap(checkpoint.tokenUsage()),
                RunMode.from(checkpoint.runMode()),
                new LinkedHashSet<>(checkpoint.activeDeferredTools() == null ? Set.of() : checkpoint.activeDeferredTools())
        ), LoopObserver.NOOP);
    }

    private void prepareExecutionPlan(LoopState state, String userMessage, LoopObserver observer) {
        if (planner == null || planManager == null || todoManager == null) {
            return;
        }
        ExecutionPlan plan = planner.plan(state.runId(), state.sessionId(), state.runMode(),
                userMessage, state.workspace());
        if (plan == null || !plan.hasSteps()) {
            return;
        }
        String contextBlock = planner.renderForContext(plan);
        if (!contextBlock.isBlank()) {
            insertBeforeLastUser(state.messages(), Message.system(contextBlock));
        }
        Map<String, Object> planPayload = planManager.payload(plan);
        try {
            planManager.save(plan);
        } catch (Exception e) {
            log.warn("[Planner] 保存执行计划失败: {}", e.getMessage());
            log.debug("[Planner] 保存执行计划失败详情", e);
        }

        Map<String, Object> todoPayload = Map.of();
        try {
            var todoState = todoManager.update(state.sessionId(), state.runId(), planTodoItems(plan));
            todoPayload = todoManager.payload(todoState);
        } catch (Exception e) {
            log.warn("[Planner] 同步 Todo 失败: {}", e.getMessage());
            log.debug("[Planner] 同步 Todo 失败详情", e);
        }

        sessionManager.addMessage(state.sessionId(), "assistant", "", Map.of(
                "source", "Jarvis",
                "trace", true,
                "hidden", true,
                "display_event", false,
                "trace_type", "execution_plan",
                "event_type", "execution_plan",
                "plan", planPayload
        ));
        recordEvent(state, "plan.created", plan.goal(), planPayload);
        observer.onPlanUpdate(state, planPayload, todoPayload);
        log.info("[Planner] 已生成执行计划: sessionId={}, runId={}, steps={}, llm={}",
                state.sessionId(), state.runId(), plan.steps().size(), plan.generatedByLlm());
    }

    /**
     * 唯一的 Agent 循环实现。
     * 普通 HTTP、SSE、人工确认恢复都通过不同 LoopState/Observer 复用这里。
     */
    private LoopOutcome runLoop(LoopState state, LoopObserver observer) {
        int iteration = state.startIteration();
        int maxIterations = state.runMode().maxIterations(agentConfig.maxIterations());
        recordEvent(state, "run.started", "", Map.of(
                "mode", state.runMode().value(),
                "workspace", state.workspace(),
                "stream", state.stream(),
                "start_iteration", iteration,
                "max_iterations", maxIterations
        ));
        while (iteration < maxIterations) {
            iteration++;
            log.info("[AgentLoop] 第 {}/{} 轮迭代: sessionId={}, mode={}, stream={}",
                    iteration, maxIterations, state.sessionId(), state.runMode().value(), state.stream());
            recordEvent(state, "iteration.started", "", Map.of("iteration", iteration));

            var tools = toolRegistry.listToolsForMode(
                    state.runMode(),
                    latestUserMessage(state),
                    state.activeDeferredTools()
            );
            middlewareChain.beforeModel(state, iteration, tools);
            ChatResponse response = state.stream()
                    ? streamChatResponse(state, tools, observer, iteration)
                    : llmProvider.chat(state.messages(), tools);
            middlewareChain.afterModel(state, iteration, response);
            state.tokenUsage().add(response.usage());
            recordEvent(state, "model.completed", response.content(), Map.of(
                    "iteration", iteration,
                    "finish_reason", response.finishReason() != null ? response.finishReason() : "",
                    "tool_calls", response.hasToolCalls() ? response.toolCalls().size() : 0,
                    "token_usage", response.usage() != null ? Map.of(
                            "prompt_tokens", response.usage().promptTokens(),
                            "completion_tokens", response.usage().completionTokens(),
                            "total_tokens", response.usage().totalTokens()
                    ) : Map.of()
            ));
            log.info("[AgentLoop] LLM 响应: finishReason={}, content长度={}, toolCalls={}",
                    response.finishReason(),
                    response.content() != null ? response.content().length() : 0,
                    response.hasToolCalls() ? response.toolCalls().size() : 0);

            if (response.hasToolCalls()) {
                var sanitizedToolCalls = toolPayloadSanitizer.sanitizeToolCalls(response.toolCalls());
                for (var tc : response.toolCalls()) {
                    log.info("[AgentLoop] 第{}轮工具调用: {}({})", iteration, tc.name(),
                            toolPayloadSanitizer.loggableArguments(tc.name(), tc.arguments()));
                    observer.onToolCall(state, iteration, toolPayloadSanitizer.sanitizeToolCall(tc));
                    recordEvent(state, "tool.call", tc.name(), Map.of(
                            "iteration", iteration,
                            "tool_call_id", tc.id(),
                            "tool_name", tc.name(),
                            "arguments", toolPayloadSanitizer.sanitizeArguments(tc.name(), tc.arguments())
                    ));
                }
                //当前工具结果加入到当前上下文
                state.messages().add(Message.assistant(sanitizedToolCalls, response.reasoningContent()));
                //发送到memory-service中进行持久化，然后自动生成摘要，提取记忆
                sessionManager.addMessage(state.sessionId(), "assistant",
                        "",
                        toolCallsMetadata(iteration, sanitizedToolCalls));

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
                promoteDeferredTools(state, toolResults);
                state.messages().add(Message.system(
                        "请反思以上工具执行结果。如果任务已完成，请直接回复用户；如果还需要更多操作，继续调用工具。"
                ));
                continue;
            }

            String finalReply = response.content() != null ? response.content() : "（无回复内容）";
            if (shouldContinueForIncompleteTodos(state, iteration)) {
                continue;
            }
            var outcome = finishFinal(state, iteration, finalReply,
                    response.finishReason() != null ? response.finishReason() : "");
            observer.onDone(outcome);
            return outcome;
        }

        String fallback = "达到最大迭代次数（" + maxIterations + "），请简化任务后重试。";
        var outcome = finishMaxIterations(state, fallback);
        observer.onDone(outcome);
        return outcome;
    }

    private boolean shouldContinueForIncompleteTodos(LoopState state, int iteration) {
        if (state.runMode() == RunMode.CHAT || todoManager == null) {
            return false;
        }
        var todoState = todoManager.load(state.sessionId());
        if (todoState.items().isEmpty()) {
            return false;
        }
        if (hasText(todoState.runId()) && !Objects.equals(todoState.runId(), state.runId())) {
            return false;
        }
        var incomplete = todoState.items().stream()
                .filter(item -> item.status() == TodoStatus.PENDING || item.status() == TodoStatus.IN_PROGRESS)
                .toList();
        if (incomplete.isEmpty()) {
            return false;
        }

        int reminderCount = intMetadata(state.outputMetadata().get(META_TODO_COMPLETION_REMINDERS));
        if (reminderCount >= MAX_TODO_COMPLETION_REMINDERS) {
            log.warn("[AgentLoop] Todo 仍未完成，但已达到提醒上限: sessionId={}, runId={}, incomplete={}",
                    state.sessionId(), state.runId(), incomplete.size());
            recordEvent(state, "todo.completion_reminder.max", "", Map.of(
                    "iteration", iteration,
                    "incomplete_count", incomplete.size(),
                    "max_reminders", MAX_TODO_COMPLETION_REMINDERS
            ));
            return false;
        }

        state.outputMetadata().put(META_TODO_COMPLETION_REMINDERS, reminderCount + 1);
        String reminder = buildTodoCompletionReminder(incomplete);
        state.messages().add(Message.system(reminder));
        log.info("[AgentLoop] Todo 未完成，阻止最终回复并继续执行: sessionId={}, runId={}, incomplete={}, reminder={}/{}",
                state.sessionId(), state.runId(), incomplete.size(), reminderCount + 1, MAX_TODO_COMPLETION_REMINDERS);
        recordEvent(state, "todo.completion_reminder", reminder, Map.of(
                "iteration", iteration,
                "incomplete_count", incomplete.size(),
                "reminder_count", reminderCount + 1
        ));
        return true;
    }

    private String buildTodoCompletionReminder(List<com.zhan.jarvis.todo.TodoItem> incomplete) {
        var sb = new StringBuilder();
        sb.append("""
                <system_reminder>
                当前 Todo 仍有未完成项。不要直接给最终答复；请继续执行下一个可执行步骤，或调用 todo_update 将已完成、失败或不再需要的步骤更新到准确状态。
                未完成项：
                """);
        for (var item : incomplete) {
            sb.append("- [")
                    .append(item.status().value())
                    .append("] ")
                    .append(item.content())
                    .append('\n');
        }
        sb.append("</system_reminder>");
        return sb.toString();
    }

    private ChatResponse streamChatResponse(LoopState state, List<ToolDefinition> tools, LoopObserver observer, int iteration) {
        var content = new StringBuilder();
        var reasoning = new StringBuilder();
        var toolBuilders = new TreeMap<Integer, ToolCallBuilder>();
        var finishReason = new AtomicReference<String>();
        var usage = new AtomicReference<>(new ChatResponse.TokenUsage(0, 0, 0));

        llmProvider.streamChat(state.messages(), tools)
                .doOnNext(delta -> {
                    if (delta.providerEvent() != null) {
                        var event = delta.providerEvent();
                        observer.onProviderEvent(state, iteration, event.type(), event.message(), event.toMap());
                        return;
                    }
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

    private void applyVisionAttachments(List<Message> messages, Map<String, Object> metadata, String userMessage) {
        if (messages == null || metadata == null) {
            return;
        }
        if (Boolean.parseBoolean(String.valueOf(metadata.get("vision_disabled")))) {
            insertBeforeLastUser(messages, Message.system(
                    "用户上传了图片，但当前 JARVIS_VISION_ENABLED=false，主模型无法读取图片内容。"
                            + "请直接告知用户需要启用视觉能力后重试，不要猜测图片内容。"));
            return;
        }
        Object raw = metadata.get("vision_image_urls");
        if (!(raw instanceof List<?> imageUrls) || imageUrls.isEmpty()) {
            return;
        }
        var parts = new ArrayList<Message.ContentPart>();
        parts.add(Message.ContentPart.text(userMessage != null && !userMessage.isBlank()
                ? userMessage
                : "请分析这些图片。"));
        for (Object item : imageUrls) {
            String url = item == null ? "" : String.valueOf(item);
            if (!url.isBlank()) {
                parts.add(Message.ContentPart.imageUrl(url));
            }
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if ("user".equals(message.role())) {
                messages.set(i, Message.user(message.content(), parts));
                return;
            }
        }
    }

    private void insertBeforeLastUser(List<Message> messages, Message notice) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                messages.add(i, notice);
                return;
            }
        }
        messages.add(notice);
    }

    private String displayMessage(Map<String, Object> metadata, String fallback) {
        if (metadata != null) {
            Object value = metadata.get("display_message");
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return fallback;
    }

    private LoopOutcome finishRequiresConfirmation(LoopState state, int iteration, String reply) {
        var metadata = withTokenUsage(state, mergedMeta(state, Map.of(
                "source", "Jarvis",
                "final", true,
                "display_event", true,
                "event_type", "confirmation_card",
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
        recordEvent(state, "run.requires_confirmation", reply, metadata);
        return new LoopOutcome(state.sessionId(), reply, iteration, "requires_confirmation", true, false,
                state.tokenUsage().toMap());
    }

    private LoopOutcome finishFinal(LoopState state, int iteration, String reply, String finishReason) {
        sessionManager.addMessage(state.sessionId(), "assistant", reply, withTokenUsage(state, mergedMeta(state, Map.of(
                "source", "Jarvis",
                "final", true,
                "display_event", true,
                "event_type", "assistant_message",
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
        recordEvent(state, "run.completed", reply, Map.of(
                "iteration", iteration,
                "finish_reason", finishReason,
                "token_usage", state.tokenUsage().toMap()
        ));
        return new LoopOutcome(state.sessionId(), reply, iteration, finishReason, false, false,
                state.tokenUsage().toMap());
    }

    private LoopOutcome finishMaxIterations(LoopState state, String reply) {
        sessionManager.addMessage(state.sessionId(), "assistant", reply, withTokenUsage(state, mergedMeta(state, Map.of(
                "source", "Jarvis",
                "final", true,
                "display_event", true,
                "event_type", "assistant_message",
                "max_iterations_reached", true
        ))));
        triggerHook(HookManager.AGENT_POST_PROCESS, state.sessionId(), state.userId(), mergedMeta(state, Map.of(
                "reply", reply,
                "iteration", state.runMode().maxIterations(agentConfig.maxIterations()),
                "finish_reason", "max_iterations",
                "max_iterations_reached", true
        )));
        recordEvent(state, "run.max_iterations", reply, Map.of(
                "max_iterations", state.runMode().maxIterations(agentConfig.maxIterations()),
                "token_usage", state.tokenUsage().toMap()
        ));
        return new LoopOutcome(state.sessionId(), reply, state.runMode().maxIterations(agentConfig.maxIterations()),
                "max_iterations", false, true,
                state.tokenUsage().toMap());
    }

    private void addToolResults(LoopState state, int iteration, List<ToolResult> toolResults,
                                LoopObserver observer, PendingConfirmation pending) {
        for (var result : toolResults) {
            if (pending != null && Objects.equals(result.toolCallId(), pending.result().toolCallId())) {
                continue;
            }
            String visibleResult = llmVisibleToolResult(result);
            state.messages().add(Message.tool(result.toolCallId(), visibleResult));
            sessionManager.addMessage(state.sessionId(), "tool", visibleResult,
                    toolResultMetadata(iteration, result, visibleResult));
            registerArtifactFromToolResult(state, result, visibleResult);
            observer.onToolResult(state, iteration,
                    new ToolResult(result.toolCallId(), result.toolName(),
                            toolPayloadSanitizer.sanitizeArguments(result.toolName(), result.arguments()),
                            visibleResult));
            recordEvent(state, "tool.result", visibleResult, Map.of(
                    "iteration", iteration,
                    "tool_call_id", result.toolCallId(),
                    "tool_name", result.toolName(),
                    "raw_result_length", result.result() != null ? result.result().length() : 0,
                    "visible_result_length", visibleResult != null ? visibleResult.length() : 0,
                    "compressed", result.result() != null && !Objects.equals(result.result(), visibleResult)
            ));
        }
    }

    private void saveCheckpoint(PendingConfirmation pending, LoopState state, int iteration) {
        if (checkpointStore == null || !hasText(pending.confirmId())) {
            return;
        }
        // 存入内存 checkpoint；第一版按 confirmId 建索引，后续可按 runId/iteration 做恢复和回滚。
        checkpointStore.put(new AgentCheckpoint(
                "checkpoint_" + UUID.randomUUID(),
                state.runId(),
                pending.confirmId(),
                pending.result().toolCallId(),
                state.sessionKey(),
                state.sessionId(),
                state.userId(),
                List.copyOf(new ArrayList<>(state.messages())),
                iteration,
                Map.copyOf(state.metadata() == null ? Map.of() : state.metadata()),
                Map.copyOf(state.outputMetadata() == null ? Map.of() : state.outputMetadata()),
                state.tokenUsage().toMap(),
                state.runMode().value(),
                Set.copyOf(state.activeDeferredTools() == null ? Set.of() : state.activeDeferredTools()),
                pendingConfirmationMetadata(pending),
                Instant.now(),
                pending.expiresAt()
        ));
    }

    private List<ToolResult> executeToolsInParallel(List<ToolCall> toolCalls, SessionKey sessionKey,
                                                    String sessionId, String userId,
                                                    Map<String, Object> metadata) {
        var results = new ArrayList<ToolResult>();
        var tasks = new ArrayList<Thread>();
        var callByThread = new java.util.IdentityHashMap<Thread, ToolCall>();
        var ctx = new ToolContext(sessionId, sessionKey, workspaceFor(metadata), userId, metadata);

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
                t.join(Duration.ofSeconds(TOOL_EXECUTION_TIMEOUT_SECONDS).toMillis());
                if (t.isAlive()) {
                    var tc = callByThread.get(t);
                    log.warn("工具执行超时: callId={}", tc != null ? tc.id() : "");
                    synchronized (results) {
                        results.add(new ToolResult(
                                tc != null ? tc.id() : "",
                                tc != null ? tc.name() : "",
                                tc != null ? tc.arguments() : "{}",
                                "工具执行超时（" + TOOL_EXECUTION_TIMEOUT_SECONDS + "秒）"
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

    private void promoteDeferredTools(LoopState state, List<ToolResult> toolResults) {
        if (state.activeDeferredTools() == null) {
            return;
        }
        for (var result : toolResults) {
            if (!ToolRegistry.TOOL_SEARCH.equals(result.toolName())) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(result.result());
                JsonNode tools = root.path("promoted_tools");
                if (!tools.isArray()) {
                    continue;
                }
                for (JsonNode item : tools) {
                    String toolName = item.asText("");
                    if (!toolName.isBlank()) {
                        state.activeDeferredTools().add(toolName);
                    }
                }
                if (!state.activeDeferredTools().isEmpty()) {
                    log.info("[AgentLoop] deferred tools 已启用: {}", state.activeDeferredTools());
                }
            } catch (Exception e) {
                log.debug("[AgentLoop] tool_search 结果解析失败: {}", e.getMessage());
            }
        }
    }

    private LoopState newState(SessionKey sessionKey, String sessionId, String userId,
                               Map<String, Object> metadata, List<Message> messages,
                               int startIteration, boolean stream, Map<String, Object> outputMetadata) {
        String runId = "run_" + UUID.randomUUID();
        var safeMetadata = new LinkedHashMap<String, Object>(metadata == null ? Map.of() : metadata);
        safeMetadata.putIfAbsent("run_id", runId);
        return new LoopState(
                runId,
                sessionKey,
                sessionId,
                userId,
                safeMetadata,
                messages,
                startIteration,
                stream,
                outputMetadata == null ? Map.of() : outputMetadata,
                new TokenUsageAccumulator(),
                RunMode.from(safeMetadata.get("mode")),
                new LinkedHashSet<>()
        );
    }

    private Map<String, Object> pendingConfirmationMetadata(PendingConfirmation pending) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("confirm_id", pending.confirmId());
        metadata.put("pending_tool_call_id", pending.result().toolCallId());
        metadata.put("tool_name", pending.result().toolName());
        metadata.put("reply", pending.reply());
        metadata.put("expires_at", pending.expiresAt() != null ? pending.expiresAt().toString() : "");
        return metadata;
    }

    private Map<String, Object> withCurrentMessage(Map<String, Object> metadata, String userMessage) {
        var merged = new LinkedHashMap<String, Object>(metadata == null ? Map.of() : metadata);
        merged.put("current_message", userMessage != null ? userMessage : "");
        merged.put("workspace", workspaceFor(metadata));
        return merged;
    }

    private List<Map<String, Object>> planTodoItems(ExecutionPlan plan) {
        var items = new ArrayList<Map<String, Object>>();
        int order = 1;
        for (var step : plan.steps()) {
            var item = new LinkedHashMap<String, Object>();
            item.put("id", step.id());
            item.put("content", step.title());
            item.put("status", step.status().value());
            item.put("order", order++);
            items.add(item);
        }
        return items;
    }

    private String workspaceFor(Map<String, Object> metadata) {
        Object raw = metadata == null ? null : metadata.get("workspace");
        return workspaceResolver.resolveWorkspace(raw);
    }

    private String latestUserMessage(LoopState state) {
        Object current = state.metadata() == null ? null : state.metadata().get("current_message");
        if (current != null && !String.valueOf(current).isBlank()) {
            return String.valueOf(current);
        }
        for (int i = state.messages().size() - 1; i >= 0; i--) {
            var message = state.messages().get(i);
            if ("user".equals(message.role()) && message.content() != null) {
                return message.content();
            }
        }
        return "";
    }

    private Map<String, Object> toolCallsMetadata(int iteration, List<ToolCall> toolCalls) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("source", "Jarvis");
        metadata.put("trace", true);
        metadata.put("hidden", true);
        metadata.put("display_event", false);
        metadata.put("trace_type", "assistant_tool_calls");
        metadata.put("iteration", iteration);
        metadata.put("tool_calls", toolCalls.stream()
                .map(toolPayloadSanitizer::toolCallMetadata)
                .toList());
        return metadata;
    }

    private String llmVisibleToolResult(ToolResult result) {
        if (result == null || result.result() == null) {
            return "";
        }
        if ("git".equals(result.toolName())) {
            String compressed = compressGitToolResult(result);
            if (compressed != null) {
                return compressed;
            }
        }
        if (result.result().length() <= MAX_VISIBLE_TOOL_RESULT_CHARS) {
            return result.result();
        }
        return toJson(Map.of(
                "tool", result.toolName(),
                "summary", "工具结果过长，已压缩。需要细节时请针对具体文件或范围重新调用工具。",
                "result_preview", result.result().substring(0, MAX_VISIBLE_TOOL_RESULT_CHARS),
                "original_chars", result.result().length(),
                "truncated", true
        ));
    }

    private String compressGitToolResult(ToolResult result) {
        try {
            JsonNode root = objectMapper.readTree(result.result());
            String action = root.path("action").asText("");
            if (!"diff".equals(action)) {
                return null;
            }

            String output = root.path("output").asText("");
            int exitCode = root.path("exit_code").asInt(0);
            boolean rawTruncated = root.path("truncated").asBoolean(false);
            var files = extractDiffStatFiles(output);
            String summary = summarizeDiff(output, files);
            var payload = new LinkedHashMap<String, Object>();
            payload.put("tool", "git.diff");
            payload.put("summary", summary);
            payload.put("files", files);
            payload.put("truncated", rawTruncated || output.length() > MAX_VISIBLE_TOOL_RESULT_CHARS
                    || files.size() >= MAX_VISIBLE_TOOL_FILES);
            payload.put("exit_code", exitCode);
            payload.put("command", root.path("command").toString());
            if (exitCode != 0) {
                payload.put("error", root.path("error").asText("Git diff 执行失败"));
                payload.put("output_preview", output.length() > 1200 ? output.substring(0, 1200) : output);
            }
            payload.put("hint", "需要具体改动时，请针对单个文件调用 git diff，设置 path 且 stat=false。");
            return toJson(payload);
        } catch (Exception e) {
            log.debug("[AgentLoop] git diff 结果压缩失败: {}", e.getMessage());
            return null;
        }
    }

    private List<String> extractDiffStatFiles(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        var files = new ArrayList<String>();
        for (String line : output.split("\\R")) {
            String stripped = line.strip();
            int marker = stripped.indexOf(" | ");
            if (marker <= 0 || stripped.contains(" file changed")
                    || stripped.contains(" files changed")) {
                continue;
            }
            String file = stripped.substring(0, marker).strip();
            if (!file.isBlank() && files.size() < MAX_VISIBLE_TOOL_FILES) {
                files.add(file);
            }
        }
        return List.copyOf(files);
    }

    private String summarizeDiff(String output, List<String> files) {
        String statLine = "";
        if (output != null) {
            for (String line : output.split("\\R")) {
                String stripped = line.strip();
                if (stripped.contains(" file changed") || stripped.contains(" files changed")) {
                    statLine = stripped;
                }
            }
        }
        if (!statLine.isBlank()) {
            return statLine;
        }
        if (!files.isEmpty()) {
            return files.size() + " 个文件修改";
        }
        return "没有 diff 输出，可能当前范围没有变更。";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"JSON 序列化失败\"}";
        }
    }

    private Map<String, Object> toolResultMetadata(int iteration, ToolResult result, String visibleResult) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("source", "Jarvis");
        metadata.put("trace", true);
        metadata.put("hidden", true);
        metadata.put("display_event", false);
        metadata.put("trace_type", "tool_result");
        metadata.put("iteration", iteration);
        metadata.put("tool_call_id", result.toolCallId());
        metadata.put("tool_name", result.toolName());
        metadata.put("arguments", toolPayloadSanitizer.sanitizeArguments(result.toolName(), result.arguments()));
        metadata.put("raw_result_length", result.result() != null ? result.result().length() : 0);
        metadata.put("visible_result_length", visibleResult != null ? visibleResult.length() : 0);
        metadata.put("compressed", result.result() != null && !Objects.equals(result.result(), visibleResult));
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

    private void recordEvent(LoopState state, String type, String content, Map<String, Object> metadata) {
        runEventStore.append(new RunEvent(
                "event_" + UUID.randomUUID(),
                state.runId(),
                state.sessionId(),
                state.userId(),
                type,
                content == null ? "" : content,
                metadata == null ? Map.of() : metadata,
                Instant.now()
        ));
    }

    private void registerArtifactFromToolResult(LoopState state, ToolResult result, String visibleResult) {
        if (artifactManager == null || result == null || visibleResult == null || visibleResult.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(visibleResult);
            String tool = root.path("tool").asText(result.toolName());
            String path = root.path("path").asText("");
            String operation = root.path("operation").asText("");
            if (!"write_file".equals(tool) || path.isBlank() || !"write".equals(operation)) {
                return;
            }
            var artifact = artifactManager.registerOutput(state.sessionId(), state.userId(), path, "write_file",
                    root.path("summary").asText("Agent 写入文件"), Map.of(
                            "tool_call_id", result.toolCallId(),
                            "operation", operation,
                            "content_chars", root.path("content_chars").asInt(0)
                    ));
            state.artifacts().add(new com.zhan.jarvis.agent.runtime.RuntimeArtifact(
                    artifact.artifactId(),
                    artifact.name(),
                    artifact.path(),
                    artifact.contentType(),
                    artifact.size(),
                    artifact.summary()
            ));
            recordEvent(state, "artifact.created", artifact.path(), Map.of(
                    "artifact_id", artifact.artifactId(),
                    "name", artifact.name(),
                    "kind", artifact.kind(),
                    "source", artifact.source(),
                    "size", artifact.size()
            ));
        } catch (Exception e) {
            log.debug("[AgentLoop] 工具结果未登记为 artifact: {}", e.getMessage());
        }
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
                // 下面执行兜底处理。
            }
        }
        return Instant.now().plus(Duration.ofMinutes(10));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static int intMetadata(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

}
