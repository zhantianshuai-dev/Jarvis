package com.zhan.jarvis.concurrency;

import com.zhan.jarvis.config.JarvisConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单实例并发治理中心。
 *
 * 虚拟线程负责承载阻塞等待，本类负责限制真正稀缺的业务资源：Agent、LLM、工具和子 Agent。
 */
public class ConcurrencyController {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyController.class);
    private static final Permit NOOP_PERMIT = () -> { };

    private final boolean enabled;
    private final int maxQueuedAgents;
    private final int maxQueuedAgentsPerUser;
    private final long acquireTimeoutMs;
    private final Semaphore agentSlots;
    private final Semaphore llmSlots;
    private final Semaphore memorySlots;
    private final Semaphore toolSlots;
    private final Semaphore subagentSlots;
    private final Semaphore asyncHookSlots;
    private final KeyedLimiter userAgents;
    private final KeyedLimiter sessionAgents;
    private final KeyedLimiter sessionTools;
    private final KeyedLimiter sessionSubagents;
    private final AtomicInteger waitingAgents = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> waitingAgentsByUser = new ConcurrentHashMap<>();
    private final AtomicInteger activeAgents = new AtomicInteger();
    private final AtomicInteger activeLlmCalls = new AtomicInteger();
    private final AtomicInteger activeMemoryCalls = new AtomicInteger();
    private final AtomicInteger activeTools = new AtomicInteger();
    private final AtomicInteger activeSubagents = new AtomicInteger();
    private final AtomicInteger activeAsyncHooks = new AtomicInteger();

    public ConcurrencyController(JarvisConfig.ConcurrencyConfig config) {
        this.enabled = config == null || config.enabled();
        int maxAgents = positive(config == null ? 0 : config.maxConcurrentAgents(), 8);
        int maxAgentsPerUser = positive(config == null ? 0 : config.maxConcurrentAgentsPerUser(), 2);
        this.maxQueuedAgents = positive(config == null ? 0 : config.maxQueuedAgents(), 128);
        this.maxQueuedAgentsPerUser = positive(config == null ? 0 : config.maxQueuedAgentsPerUser(), 16);
        int maxLlm = positive(config == null ? 0 : config.maxConcurrentLlmCalls(), 8);
        int maxMemory = positive(config == null ? 0 : config.maxConcurrentMemoryCalls(), 16);
        int maxTools = positive(config == null ? 0 : config.maxConcurrentTools(), 16);
        int maxToolsPerSession = positive(config == null ? 0 : config.maxConcurrentToolsPerSession(), 4);
        int maxSubagents = positive(config == null ? 0 : config.maxConcurrentSubagents(), 8);
        int maxSubagentsPerSession = positive(config == null ? 0 : config.maxConcurrentSubagentsPerSession(), 3);
        int maxAsyncHooks = positive(config == null ? 0 : config.maxConcurrentAsyncHooks(), 32);
        this.acquireTimeoutMs = positive(config == null ? 0 : config.acquireTimeoutMs(), 30_000L);

        this.agentSlots = new Semaphore(maxAgents, true);
        this.llmSlots = new Semaphore(maxLlm, true);
        this.memorySlots = new Semaphore(maxMemory, true);
        this.toolSlots = new Semaphore(maxTools, true);
        this.subagentSlots = new Semaphore(maxSubagents, true);
        this.asyncHookSlots = new Semaphore(maxAsyncHooks, true);
        this.userAgents = new KeyedLimiter(maxAgentsPerUser);
        this.sessionAgents = new KeyedLimiter(1);
        this.sessionTools = new KeyedLimiter(maxToolsPerSession);
        this.sessionSubagents = new KeyedLimiter(maxSubagentsPerSession);

        log.info("并发治理初始化: enabled={}, agents={}, perUser={}, queued={}, llm={}, tools={}, subagents={}",
                enabled, maxAgents, maxAgentsPerUser, maxQueuedAgents, maxLlm, maxTools, maxSubagents);
    }

    /** 获取主 Agent 执行许可；同一会话强制串行。 */
    public Permit acquireAgent(String userId, String sessionId) {
        if (!enabled) {
            return NOOP_PERMIT;
        }
        int waiting = waitingAgents.incrementAndGet();
        if (waiting > maxQueuedAgents) {
            waitingAgents.decrementAndGet();
            throw new SystemBusyException("Agent 等待队列已满，请稍后重试");
        }
        String normalizedUserId = key(userId, "anonymous");
        int userWaiting = waitingAgentsByUser
                .computeIfAbsent(normalizedUserId, ignored -> new AtomicInteger())
                .incrementAndGet();
        if (userWaiting > maxQueuedAgentsPerUser) {
            decrementUserWaiting(normalizedUserId);
            waitingAgents.decrementAndGet();
            throw new SystemBusyException("当前用户等待中的任务过多，请稍后重试");
        }

        var acquired = new ArrayList<Permit>(3);
        long deadline = deadline();
        try {
            // 先等待会话锁，避免同会话请求占住全局 Agent 配额。
            acquired.add(sessionAgents.acquire(key(sessionId, "anonymous-session"), remaining(deadline),
                    "同一会话已有任务正在执行，请稍后重试"));
            acquired.add(userAgents.acquire(normalizedUserId, remaining(deadline),
                    "当前用户并发任务数已达上限，请稍后重试"));
            acquired.add(acquire(agentSlots, remaining(deadline), "Agent 并发数已达上限，请稍后重试"));
            activeAgents.incrementAndGet();
            return combined(acquired, activeAgents);
        } catch (RuntimeException e) {
            closeReverse(acquired);
            throw e;
        } finally {
            decrementUserWaiting(normalizedUserId);
            waitingAgents.decrementAndGet();
        }
    }

    /** 获取一次完整 LLM 调用许可，流式调用会持有到流结束。 */
    public Permit acquireLlm() {
        return acquireCounted(llmSlots, activeLlmCalls, "LLM 并发数已达上限，请稍后重试");
    }

    /** 获取 memory-service 调用许可，避免阻塞请求无限压向下游服务。 */
    public Permit acquireMemory() {
        return acquireCounted(memorySlots, activeMemoryCalls, "memory-service 并发数已达上限，请稍后重试");
    }

    /** 获取工具执行许可；编排类工具不占用普通工具配额。 */
    public Permit acquireTool(String toolName, String sessionId) {
        if (!enabled || isOrchestrationTool(toolName)) {
            return NOOP_PERMIT;
        }
        var acquired = new ArrayList<Permit>(2);
        long deadline = deadline();
        try {
            acquired.add(sessionTools.acquire(key(sessionId, "unknown-session"), remaining(deadline),
                    "当前会话的工具并发数已达上限"));
            acquired.add(acquire(toolSlots, remaining(deadline), "工具执行并发数已达上限，请稍后重试"));
            activeTools.incrementAndGet();
            return combined(acquired, activeTools);
        } catch (RuntimeException e) {
            closeReverse(acquired);
            throw e;
        }
    }

    /** 获取子 Agent 许可；限制全局数量和单个父会话的派生数量。 */
    public Permit acquireSubagent(String parentSessionId) {
        if (!enabled) {
            return NOOP_PERMIT;
        }
        var acquired = new ArrayList<Permit>(2);
        long deadline = deadline();
        try {
            acquired.add(sessionSubagents.acquire(key(parentSessionId, "unknown-session"), remaining(deadline),
                    "当前会话的子 Agent 并发数已达上限"));
            acquired.add(acquire(subagentSlots, remaining(deadline), "子 Agent 并发数已达上限，请稍后重试"));
            activeSubagents.incrementAndGet();
            return combined(acquired, activeSubagents);
        } catch (RuntimeException e) {
            closeReverse(acquired);
            throw e;
        }
    }

    /**
     * 异步 Hook 属于旁路观察逻辑，不等待配额；容量满时由调用方直接丢弃，避免形成隐藏队列。
     */
    public Permit tryAcquireAsyncHook() {
        if (!enabled) {
            return NOOP_PERMIT;
        }
        if (!asyncHookSlots.tryAcquire()) {
            return null;
        }
        activeAsyncHooks.incrementAndGet();
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                activeAsyncHooks.decrementAndGet();
                asyncHookSlots.release();
            }
        };
    }

    public Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        result.put("enabled", enabled);
        result.put("active_agents", activeAgents.get());
        result.put("waiting_agents", waitingAgents.get());
        result.put("active_llm_calls", activeLlmCalls.get());
        result.put("active_memory_calls", activeMemoryCalls.get());
        result.put("active_tools", activeTools.get());
        result.put("active_subagents", activeSubagents.get());
        result.put("active_async_hooks", activeAsyncHooks.get());
        result.put("agent_available_permits", agentSlots.availablePermits());
        result.put("llm_available_permits", llmSlots.availablePermits());
        result.put("memory_available_permits", memorySlots.availablePermits());
        result.put("tool_available_permits", toolSlots.availablePermits());
        result.put("subagent_available_permits", subagentSlots.availablePermits());
        result.put("async_hook_available_permits", asyncHookSlots.availablePermits());
        return Collections.unmodifiableMap(result);
    }

    private Permit acquireCounted(Semaphore semaphore, AtomicInteger counter, String message) {
        if (!enabled) {
            return NOOP_PERMIT;
        }
        Permit permit = acquire(semaphore, acquireTimeoutMs, message);
        counter.incrementAndGet();
        return combined(new ArrayList<>(java.util.List.of(permit)), counter);
    }

    private Permit acquire(Semaphore semaphore, long timeoutMs, String message) {
        try {
            if (!semaphore.tryAcquire(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS)) {
                throw new SystemBusyException(message);
            }
            return semaphore::release;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SystemBusyException("等待并发许可时被中断", e);
        }
    }

    private Permit combined(ArrayList<Permit> permits, AtomicInteger counter) {
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                counter.decrementAndGet();
                closeReverse(permits);
            }
        };
    }

    private static void closeReverse(java.util.List<Permit> permits) {
        for (int i = permits.size() - 1; i >= 0; i--) {
            permits.get(i).close();
        }
    }

    private long deadline() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(acquireTimeoutMs);
    }

    private static long remaining(long deadline) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    private static boolean isOrchestrationTool(String toolName) {
        return "spawn".equals(toolName) || "tool_search".equals(toolName) || "todo_update".equals(toolName);
    }

    private void decrementUserWaiting(String userId) {
        waitingAgentsByUser.computeIfPresent(userId, (ignored, counter) ->
                counter.decrementAndGet() == 0 ? null : counter);
    }

    private static String key(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    @FunctionalInterface
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    private static final class KeyedLimiter {
        private final int permits;
        private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

        private KeyedLimiter(int permits) {
            this.permits = permits;
        }

        private Permit acquire(String key, long timeoutMs, String message) {
            Entry entry = entries.compute(key, (ignored, current) -> {
                Entry value = current == null ? new Entry(permits) : current;
                value.references++;
                return value;
            });
            boolean acquired = false;
            try {
                acquired = entry.semaphore.tryAcquire(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
                if (!acquired) {
                    throw new SystemBusyException(message);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SystemBusyException("等待并发许可时被中断", e);
            } finally {
                if (!acquired) {
                    releaseReference(key, entry);
                }
            }

            var closed = new java.util.concurrent.atomic.AtomicBoolean();
            return () -> {
                if (closed.compareAndSet(false, true)) {
                    entry.semaphore.release();
                    releaseReference(key, entry);
                }
            };
        }

        private void releaseReference(String key, Entry expected) {
            entries.computeIfPresent(key, (ignored, current) -> {
                if (current != expected) {
                    return current;
                }
                current.references--;
                return current.references == 0 ? null : current;
            });
        }

        private static final class Entry {
            private final Semaphore semaphore;
            private int references;

            private Entry(int permits) {
                this.semaphore = new Semaphore(permits, true);
            }
        }
    }
}
