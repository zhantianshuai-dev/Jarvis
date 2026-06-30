package com.zhan.jarvis.subagent;

import com.zhan.jarvis.channel.SessionKey;
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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子 Agent 管理器 — 对标 Python VikingBot SubagentManager。
 * <p>
 * 管理子 Agent 生命周期：创建、调度、结果收集。
 * 子 Agent 在 Virtual Thread 中运行，完成后结果存入内存。
 */
public class SubagentManager {

    private static final Logger log = LoggerFactory.getLogger(SubagentManager.class);

    private final ToolRegistry toolRegistry;
    private final AgentLLMProvider llmProvider;
    private final MemoryServiceClient memoryClient;
    private final ObjectMapper objectMapper;
    private final WorktreeManager worktreeManager;
    private final TaskManager taskManager;
    private final SseEventHub sseEventHub;
    private final String workspaceDir;

    /** 已完成和运行中的子 Agent 结果缓存 */
    private final ConcurrentHashMap<String, SubagentResult> results = new ConcurrentHashMap<>();

    public SubagentManager(ToolRegistry toolRegistry, AgentLLMProvider llmProvider,
                            MemoryServiceClient memoryClient, ObjectMapper objectMapper,
                            WorktreeManager worktreeManager, TaskManager taskManager,
                            SseEventHub sseEventHub, String workspaceDir) {
        this.toolRegistry = toolRegistry;
        this.llmProvider = llmProvider;
        this.memoryClient = memoryClient;
        this.objectMapper = objectMapper;
        this.worktreeManager = worktreeManager;
        this.taskManager = taskManager;
        this.sseEventHub = sseEventHub;
        this.workspaceDir = workspaceDir;
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
        //新建taskID
        String taskId = UUID.randomUUID().toString();
        try {
            //创建任务
            taskManager.createSubagentTask(taskId, parentUserId, parentSessionId,
                    parentSessionKey == null ? "" : parentSessionKey.canonical(), task);
        } catch (Exception e) {
            return SpawnHandle.failed(taskId, "创建任务记录失败: " + e.getMessage());
        }

        var metadata = new LinkedHashMap<String, Object>(parentMetadata == null ? Map.of() : parentMetadata);

        String resolvedWorktreeName = safeValue(worktreeName);
        if (createWorktree && resolvedWorktreeName.isBlank()) {
            resolvedWorktreeName = defaultWorktreeName(task, taskId);
        }

        String worktreePath = "";
        if (!resolvedWorktreeName.isBlank()) {
            try {
                //如果参数中createWorktree==true，就会为子agent创建一个独立的工作区
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

        var subagent = new SubagentLoop(taskId, task, toolRegistry, llmProvider, memoryClient,
                objectMapper, workspaceDir, parentSessionId, parentSessionKey, parentUserId, metadata);

        results.put(taskId, SubagentResult.running(taskId));
        try {
            taskManager.markRunning(taskId);
        } catch (Exception e) {
            log.warn("[SubagentManager] 标记任务 running 失败: taskId={}, error={}", taskId, e.getMessage());
        }
        publishSubagentStatus(parentSessionId, taskId, task, "running", resolvedWorktreeName, worktreePath, "", "");
        String boundWorktreeName = resolvedWorktreeName;
        String boundWorktreePath = worktreePath;

        // Virtual Thread 后台执行
        Thread.startVirtualThread(() -> {
            log.info("[SubagentManager] 启动子 Agent: taskId={}, task={}", taskId, task);
            SubagentResult result = subagent.run();
            results.put(taskId, result);
            persistFinalStatus(result);
            if ("completed".equals(result.status())) {
                publishSubagentStatus(parentSessionId, taskId, task, "completed",
                        boundWorktreeName, boundWorktreePath, result.result(), "");
                if (persistChatStatusOnCompletion) {
                    persistSubagentChatStatus(parentSessionId, taskId, task, "completed",
                            boundWorktreeName, result.result(), "");
                }
            } else if ("failed".equals(result.status())) {
                publishSubagentStatus(parentSessionId, taskId, task, "failed",
                        boundWorktreeName, boundWorktreePath, "", result.error());
                if (persistChatStatusOnCompletion) {
                    persistSubagentChatStatus(parentSessionId, taskId, task, "failed",
                            boundWorktreeName, "", result.error());
                }
            }
            log.info("[SubagentManager] 子 Agent 完成: taskId={}, status={}", taskId, result.status());
        });

        return SpawnHandle.started(taskId, resolvedWorktreeName, worktreePath);
    }

    /** 获取子 Agent 结果 */
    public SubagentResult getResult(String taskId) {
        return results.get(taskId);
    }

    /** 等待子 Agent 完成（阻塞） */
    public SubagentResult waitForCompletion(String taskId, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            var result = results.get(taskId);
            if (result != null && !"running".equals(result.status())) {
                return result;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return SubagentResult.failed(taskId, "等待被中断");
            }
        }
        return SubagentResult.failed(taskId, "等待超时 (" + timeoutMs + "ms)");
    }

    /** 获取运行中的子 Agent 数量 */
    public int activeCount() {
        return (int) results.values().stream()
                .filter(r -> "running".equals(r.status()))
                .count();
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

    private void persistFinalStatus(SubagentResult result) {
        try {
            if ("completed".equals(result.status())) {
                taskManager.complete(result.taskId(), result.result());
            } else if ("failed".equals(result.status())) {
                taskManager.fail(result.taskId(), result.error());
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
                : "子 Agent 执行失败。\n\n任务 ID: " + taskId + "\n任务: " + task + "\n错误: " + safeValue(error);
        try {
            memoryClient.addMessage(sessionId, "assistant", content, Map.of(
                    "source", "Jarvis",
                    "final", true,
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
}
