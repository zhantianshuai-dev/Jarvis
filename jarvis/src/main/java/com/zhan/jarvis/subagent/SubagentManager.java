package com.zhan.jarvis.subagent;

import com.zhan.jarvis.agent.control.TurnCancellationSource;
import com.zhan.jarvis.agent.control.TurnCancellationToken;
import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.concurrency.ConcurrencyController;
import com.zhan.jarvis.concurrency.SystemBusyException;
import com.zhan.jarvis.git.WorktreeManager;
import com.zhan.jarvis.llm.AgentLLMProvider;
import com.zhan.jarvis.memory.MemoryServiceClient;
import com.zhan.jarvis.server.sse.SseEventHub;
import com.zhan.jarvis.server.sse.SseEventTypes;
import com.zhan.jarvis.task.TaskManager;
import com.zhan.jarvis.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 子 Agent 管理器 — 子 Agent 管理器。
 * <p>
 * 管理子 Agent 生命周期：创建、调度、结果收集。
 * 子 Agent 在虚拟线程中运行，完成后结果存入内存。
 */
public class SubagentManager {

    private static final Logger log = LoggerFactory.getLogger(SubagentManager.class);
    private static final Duration DEFAULT_MAX_RUNTIME = Duration.ofMinutes(10);

    private final ToolRegistry toolRegistry;
    private final AgentLLMProvider llmProvider;
    private final MemoryServiceClient memoryClient;
    private final ObjectMapper objectMapper;
    private final WorktreeManager worktreeManager;
    private final TaskManager taskManager;
    private final SseEventHub sseEventHub;
    private final String workspaceDir;
    private final ConcurrencyController concurrencyController;
    private final Duration maxRuntime;

    /** 已完成和运行中的子 Agent 结果缓存 */
    private final ConcurrentHashMap<String, SubagentResult> results = new ConcurrentHashMap<>();
    /** 子 Agent 完成信号；等待方由 complete 精确唤醒，不再定时轮询。 */
    private final ConcurrentHashMap<String, CompletableFuture<SubagentResult>> completions = new ConcurrentHashMap<>();
    /** 正在执行的子 Agent 控制句柄，用于父 Run 取消时级联终止。 */
    private final ConcurrentHashMap<String, SubagentExecution> executions = new ConcurrentHashMap<>();

    public SubagentManager(ToolRegistry toolRegistry, AgentLLMProvider llmProvider,
                            MemoryServiceClient memoryClient, ObjectMapper objectMapper,
                            WorktreeManager worktreeManager, TaskManager taskManager,
                            SseEventHub sseEventHub, String workspaceDir) {
        this(toolRegistry, llmProvider, memoryClient, objectMapper, worktreeManager, taskManager,
                sseEventHub, workspaceDir, null, DEFAULT_MAX_RUNTIME);
    }

    public SubagentManager(ToolRegistry toolRegistry, AgentLLMProvider llmProvider,
                            MemoryServiceClient memoryClient, ObjectMapper objectMapper,
                            WorktreeManager worktreeManager, TaskManager taskManager,
                            SseEventHub sseEventHub, String workspaceDir,
                            ConcurrencyController concurrencyController) {
        this(toolRegistry, llmProvider, memoryClient, objectMapper, worktreeManager, taskManager,
                sseEventHub, workspaceDir, concurrencyController, DEFAULT_MAX_RUNTIME);
    }

    public SubagentManager(ToolRegistry toolRegistry, AgentLLMProvider llmProvider,
                           MemoryServiceClient memoryClient, ObjectMapper objectMapper,
                           WorktreeManager worktreeManager, TaskManager taskManager,
                           SseEventHub sseEventHub, String workspaceDir,
                           ConcurrencyController concurrencyController, long maxRuntimeSeconds) {
        this(toolRegistry, llmProvider, memoryClient, objectMapper, worktreeManager, taskManager,
                sseEventHub, workspaceDir, concurrencyController,
                maxRuntimeSeconds > 0 ? Duration.ofSeconds(maxRuntimeSeconds) : DEFAULT_MAX_RUNTIME);
    }

    SubagentManager(ToolRegistry toolRegistry, AgentLLMProvider llmProvider,
                    MemoryServiceClient memoryClient, ObjectMapper objectMapper,
                    WorktreeManager worktreeManager, TaskManager taskManager,
                    SseEventHub sseEventHub, String workspaceDir,
                    ConcurrencyController concurrencyController, Duration maxRuntime) {
        this.toolRegistry = toolRegistry;
        this.llmProvider = llmProvider;
        this.memoryClient = memoryClient;
        this.objectMapper = objectMapper;
        this.worktreeManager = worktreeManager;
        this.taskManager = taskManager;
        this.sseEventHub = sseEventHub;
        this.workspaceDir = workspaceDir;
        this.concurrencyController = concurrencyController != null
                ? concurrencyController
                : new ConcurrencyController(null);
        this.maxRuntime = maxRuntime == null || maxRuntime.isZero() || maxRuntime.isNegative()
                ? DEFAULT_MAX_RUNTIME : maxRuntime;
    }

    /**
     * 派生子 Agent 后台执行任务。
     *
     * @param task            任务描述
     * @param parentSessionId 主 Agent 的会话 ID
     * @return 任务 ID（父 Agent 可据此获取结果）
     */
    public SpawnHandle spawn(String task, String parentSessionId, SessionKey parentSessionKey, String parentUserId,
                             Map<String, Object> parentMetadata, boolean createWorktree, String worktreeName) {
        return spawn(task, parentSessionId, parentSessionKey, parentUserId, parentMetadata,
                createWorktree, worktreeName, true);
    }

    public SpawnHandle spawn(String task, String parentSessionId, SessionKey parentSessionKey, String parentUserId,
                             Map<String, Object> parentMetadata, boolean createWorktree, String worktreeName,
                             boolean persistChatStatusOnCompletion) {
        String parentRunId = parentMetadata == null ? ""
                : String.valueOf(parentMetadata.getOrDefault("run_id", ""));
        return spawn(task, parentSessionId, parentSessionKey, parentUserId, parentMetadata,
                createWorktree, worktreeName, persistChatStatusOnCompletion,
                parentRunId, TurnCancellationToken.none());
    }

    public SpawnHandle spawn(String task, String parentSessionId, SessionKey parentSessionKey, String parentUserId,
                             Map<String, Object> parentMetadata, boolean createWorktree, String worktreeName,
                             boolean persistChatStatusOnCompletion, String parentRunId,
                             TurnCancellationToken parentCancellationToken) {
        // 新建任务 ID。
        String taskId = UUID.randomUUID().toString();
        try {
            //创建任务
            taskManager.createSubagentTask(taskId, parentUserId, parentSessionId,
                    parentSessionKey == null ? "" : parentSessionKey.canonical(), task);
        } catch (Exception e) {
            return SpawnHandle.failed(taskId, "创建任务记录失败: " + e.getMessage());
        }

        var metadata = filteredSubagentMetadata(parentMetadata);

        String resolvedWorktreeName = safeValue(worktreeName);
        if (createWorktree && resolvedWorktreeName.isBlank()) {
            resolvedWorktreeName = defaultWorktreeName(task, taskId);
        }

        String worktreePath = "";
        if (!resolvedWorktreeName.isBlank()) {
            try {
                // 如果参数中 createWorktree==true，就会为子 Agent 创建一个独立的工作区。
                if (createWorktree) {
                    var created = worktreeManager.create(resolvedWorktreeName, "HEAD", taskId);
                    if (!created.success()) {
                        failTaskQuietly(taskId, "创建 worktree 失败: " + created.error());
                        return SpawnHandle.failed(taskId, "创建 worktree 失败: " + created.error());
                    }
                }
                Path path = worktreeManager.path(resolvedWorktreeName);
                worktreePath = path.toString();
                var bound = worktreeManager.bindTask(resolvedWorktreeName, taskId);
                if (!bound.success()) {
                    failTaskQuietly(taskId, "绑定 worktree 任务关系失败: " + bound.error());
                    return SpawnHandle.failed(taskId, "绑定 worktree 任务关系失败: " + bound.error());
                }
                taskManager.bindWorktree(taskId, resolvedWorktreeName, worktreePath);
                metadata.put("worktree", resolvedWorktreeName);
                metadata.put("worktree_path", worktreePath);
            } catch (Exception e) {
                failTaskQuietly(taskId, "绑定 worktree 失败: " + e.getMessage());
                return SpawnHandle.failed(taskId, "绑定 worktree 失败: " + e.getMessage());
            }
        }

        var cancellationSource = TurnCancellationSource.linkedTo(parentCancellationToken);
        var subagent = new SubagentLoop(taskId, task, toolRegistry, llmProvider, memoryClient,
                objectMapper, workspaceDir, parentSessionId, parentSessionKey, parentUserId, metadata,
                cancellationSource.token());

        final ConcurrencyController.Permit subagentPermit;
        try {
            subagentPermit = concurrencyController.acquireSubagent(parentSessionId);
        } catch (SystemBusyException e) {
            failTaskQuietly(taskId, e.getMessage());
            return SpawnHandle.failed(taskId, e.getMessage());
        }

        results.put(taskId, SubagentResult.running(taskId));
        var completion = new CompletableFuture<SubagentResult>();
        completions.put(taskId, completion);
        try {
            taskManager.markRunning(taskId);
        } catch (Exception e) {
            log.warn("[SubagentManager] 标记任务 running 失败: taskId={}, error={}", taskId, e.getMessage());
        }
        publishSubagentStatus(parentSessionId, taskId, task, "running", resolvedWorktreeName, worktreePath, "", "");
        String boundWorktreeName = resolvedWorktreeName;
        String boundWorktreePath = worktreePath;
        var completionControl = new CompletionControl(
                completion,
                new AtomicBoolean(),
                new AtomicBoolean(persistChatStatusOnCompletion),
                new CompletionContext(parentSessionId, taskId, task, boundWorktreeName, boundWorktreePath)
        );

        // 虚拟线程后台执行
        try {
            Thread thread = Thread.ofVirtual().unstarted(() -> {
                try {
                    try (subagentPermit) {
                        log.info("[SubagentManager] 启动子 Agent: taskId={}, task={}", taskId, task);
                        SubagentResult result;
                        try {
                            result = subagent.run();
                        } catch (Throwable e) {
                            result = SubagentResult.failed(taskId,
                                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                        }
                        completeExecution(completionControl, result);
                    }
                } finally {
                    executions.remove(taskId);
                    cancellationSource.close();
                    scheduleResultCleanup(taskId, completion);
                }
            });
            var execution = new SubagentExecution(taskId, safeValue(parentRunId), thread,
                    cancellationSource, completionControl, subagentPermit);
            executions.put(taskId, execution);
            thread.start();
            scheduleExecutionTimeout(execution);
        } catch (RuntimeException e) {
            executions.remove(taskId);
            cancellationSource.close();
            subagentPermit.close();
            completions.remove(taskId, completion);
            var failed = SubagentResult.failed(taskId, "启动子 Agent 虚拟线程失败: " + e.getMessage());
            results.put(taskId, failed);
            persistFinalStatus(failed);
            return SpawnHandle.failed(taskId, failed.error());
        }

        return SpawnHandle.started(taskId, resolvedWorktreeName, worktreePath);
    }

    static Map<String, Object> filteredSubagentMetadata(Map<String, Object> parentMetadata) {
        var metadata = new LinkedHashMap<String, Object>();
        copyIfPresent(parentMetadata, metadata, "workspace");
        copyIfPresent(parentMetadata, metadata, "mode");
        copyIfPresent(parentMetadata, metadata, "channel_type");
        copyIfPresent(parentMetadata, metadata, "chat_id");
        copyIfPresent(parentMetadata, metadata, "open_id");
        copyIfPresent(parentMetadata, metadata, "feishu_open_id");
        copyIfPresent(parentMetadata, metadata, "allowed_tool_groups");
        metadata.put("subagent_isolated", true);
        return metadata;
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || !source.containsKey(key)) {
            return;
        }
        Object value = source.get(key);
        if (value == null) {
            return;
        }
        if (value instanceof String s && s.isBlank()) {
            return;
        }
        if (value instanceof List<?> list) {
            target.put(key, List.copyOf(list));
            return;
        }
        target.put(key, value);
    }

    /** 获取子 Agent 结果 */
    public SubagentResult getResult(String taskId) {
        var cached = results.get(taskId);
        if (cached != null) {
            return cached;
        }
        return taskManager.get(taskId)
                .map(task -> new SubagentResult(task.taskId(), task.status(),
                        blankToNull(task.result()), blankToNull(task.error())))
                .orElse(null);
    }

    /** 等待子 Agent 完成（阻塞） */
    public SubagentResult waitForCompletion(String taskId, long timeoutMs) {
        var cached = getResult(taskId);
        if (cached != null && !"running".equals(cached.status()) && !"pending".equals(cached.status())) {
            return cached;
        }
        var completion = completions.get(taskId);
        if (completion == null) {
            return cached != null ? cached : SubagentResult.failed(taskId, "未找到子 Agent 任务");
        }
        try {
            return completion.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 同步等待已经结束，后续完成状态需要像后台任务一样持久化到会话，避免 SSE 断开后丢失。
            var execution = executions.get(taskId);
            if (execution != null) {
                execution.completionControl().persistChatStatus().set(true);
            }
            return SubagentResult.failed(taskId, "等待超时 (" + timeoutMs + "ms)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SubagentResult.failed(taskId, "等待被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return SubagentResult.failed(taskId, cause.getMessage());
        }
    }

    /** 获取运行中的子 Agent 数量 */
    public int activeCount() {
        return (int) results.values().stream()
                .filter(r -> "running".equals(r.status()))
                .count();
    }

    /** 父 Run 终止时取消它派生出的全部活动子 Agent。 */
    public int cancelByParentRun(String parentRunId, String reason) {
        if (parentRunId == null || parentRunId.isBlank()) {
            return 0;
        }
        int cancelled = 0;
        for (SubagentExecution execution : executions.values()) {
            if (!parentRunId.equals(execution.parentRunId())) {
                continue;
            }
            execution.source().cancel(reason);
            execution.thread().interrupt();
            cancelled++;
        }
        return cancelled;
    }

    private static String defaultWorktreeName(String task, String taskId) {
        String base = safeValue(task)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isBlank()) {
            base = "subagent";
        }
        if (base.length() > 40) {
            base = base.substring(0, 40).replaceAll("-+$", "");
        }
        return base + "-" + taskId.substring(0, 8);
    }

    private static String safeValue(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void scheduleExecutionTimeout(SubagentExecution execution) {
        long timeoutMs = Math.max(1, maxRuntime.toMillis());
        CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS).execute(() -> {
            String reason = "子 Agent 超过最大运行时间 " + maxRuntime.toSeconds() + " 秒";
            var timedOut = SubagentResult.timedOut(execution.taskId(), reason);
            if (!claimCompletion(execution.completionControl(), timedOut)) {
                return;
            }

            log.warn("[SubagentManager] 子 Agent 执行超时，正在终止: taskId={}, maxRuntimeMs={}",
                    execution.taskId(), timeoutMs);
            execution.source().cancel(reason);
            execution.thread().interrupt();
            // Permit 本身是幂等的；即使被中断的线程稍后退出并再次 close，也不会重复释放。
            execution.permit().close();
            executions.remove(execution.taskId(), execution);
            finishClaimedCompletion(execution.completionControl(), timedOut);
            scheduleResultCleanup(execution.taskId(), execution.completionControl().completion());
        });
    }

    private void completeExecution(CompletionControl control, SubagentResult result) {
        if (!claimCompletion(control, result)) {
            log.debug("[SubagentManager] 忽略迟到的子 Agent 结果: taskId={}, status={}",
                    result.taskId(), result.status());
            return;
        }
        finishClaimedCompletion(control, result);
    }

    private boolean claimCompletion(CompletionControl control, SubagentResult result) {
        if (!control.terminal().compareAndSet(false, true)) {
            return false;
        }
        results.put(result.taskId(), result);
        return true;
    }

    private void finishClaimedCompletion(CompletionControl control, SubagentResult result) {
        CompletionContext ctx = control.context();
        persistFinalStatus(result);
        control.completion().complete(result);

        String resultText = "completed".equals(result.status()) ? safeValue(result.result()) : "";
        String errorText = "completed".equals(result.status()) ? "" : safeValue(result.error());
        publishSubagentStatus(ctx.parentSessionId(), ctx.taskId(), ctx.task(), result.status(),
                ctx.worktreeName(), ctx.worktreePath(), resultText, errorText);
        if (control.persistChatStatus().get()) {
            persistSubagentChatStatus(ctx.parentSessionId(), ctx.taskId(), ctx.task(), result.status(),
                    ctx.worktreeName(), resultText, errorText);
        }
        log.info("[SubagentManager] 子 Agent 结束: taskId={}, status={}", result.taskId(), result.status());
    }

    private void scheduleResultCleanup(String taskId, CompletableFuture<SubagentResult> completion) {
        CompletableFuture.delayedExecutor(10, TimeUnit.MINUTES).execute(() -> {
            completions.remove(taskId, completion);
            results.remove(taskId);
        });
    }

    private void persistFinalStatus(SubagentResult result) {
        try {
            if ("completed".equals(result.status())) {
                taskManager.complete(result.taskId(), result.result());
            } else if ("failed".equals(result.status())) {
                taskManager.fail(result.taskId(), result.error());
            } else if ("cancelled".equals(result.status())) {
                taskManager.cancel(result.taskId(), result.error());
            } else if ("timed_out".equals(result.status())) {
                taskManager.timeout(result.taskId(), result.error());
            }
        } catch (Exception e) {
            log.warn("[SubagentManager] 持久化任务最终状态失败: taskId={}, error={}",
                    result.taskId(), e.getMessage());
        }
    }

    private void failTaskQuietly(String taskId, String error) {
        try {
            taskManager.fail(taskId, error);
        } catch (Exception e) {
            log.warn("[SubagentManager] 标记任务失败状态失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    private void publishSubagentStatus(String sessionId, String taskId, String task, String status,
                                       String worktreeName, String worktreePath, String result, String error) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String content = switch (status) {
            case "running" -> "子 Agent 正在运行";
            case "completed" -> "子 Agent 执行成功";
            case "failed" -> "子 Agent 执行失败";
            case "cancelled" -> "子 Agent 已停止";
            case "timed_out" -> "子 Agent 执行超时";
            default -> "子 Agent 状态更新";
        };
        sseEventHub.publish(sessionId, SseEventTypes.SUBAGENT_STATUS, content, "subagent", Map.of(
                "task_id", taskId,
                "task", task,
                "status", status,
                "worktree", safeValue(worktreeName),
                "worktree_path", safeValue(worktreePath),
                "result", safeValue(result),
                "error", safeValue(error)
        ));
    }

    private void persistSubagentChatStatus(String sessionId, String taskId, String task, String status,
                                           String worktreeName, String result, String error) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String content = "completed".equals(status)
                ? "子 Agent 执行成功。\n\n任务 ID: " + taskId + "\n任务: " + task + resultBlock(result)
                : "cancelled".equals(status)
                ? "子 Agent 已停止。\n\n任务 ID: " + taskId + "\n任务: " + task + "\n原因: " + safeValue(error)
                : "timed_out".equals(status)
                ? "子 Agent 执行超时。\n\n任务 ID: " + taskId + "\n任务: " + task + "\n原因: " + safeValue(error)
                : "子 Agent 执行失败。\n\n任务 ID: " + taskId + "\n任务: " + task + "\n错误: " + safeValue(error);
        try {
            memoryClient.addMessage(sessionId, "assistant", content, Map.of(
                    "source", "Jarvis",
                    "final", true,
                    "display_event", true,
                    "event_type", "subagent_status",
                    "subagent_status", true,
                    "task_id", taskId,
                    "task", task,
                    "status", status,
                    "worktree", safeValue(worktreeName)
            ));
        } catch (Exception e) {
            log.debug("[SubagentManager] 子 Agent 状态写入会话失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    private static String resultBlock(String result) {
        String value = safeValue(result);
        if (value.isBlank()) {
            return "";
        }
        if (value.length() > 4000) {
            value = value.substring(0, 4000) + "\n...[truncated]";
        }
        return "\n\n结果:\n" + value;
    }

    public record SpawnHandle(boolean started, String taskId, String worktreeName, String worktreePath, String error) {
        static SpawnHandle started(String taskId, String worktreeName, String worktreePath) {
            return new SpawnHandle(true, taskId, safeValue(worktreeName), safeValue(worktreePath), "");
        }

        static SpawnHandle failed(String taskId, String error) {
            return new SpawnHandle(false, taskId, "", "", error);
        }
    }

    private record CompletionContext(String parentSessionId, String taskId, String task,
                                     String worktreeName, String worktreePath) {
    }

    private record CompletionControl(CompletableFuture<SubagentResult> completion,
                                     AtomicBoolean terminal, AtomicBoolean persistChatStatus,
                                     CompletionContext context) {
    }

    private record SubagentExecution(String taskId, String parentRunId, Thread thread,
                                     TurnCancellationSource source, CompletionControl completionControl,
                                     ConcurrencyController.Permit permit) {
    }
}
