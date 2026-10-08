package com.zhan.jarvis.hook;

import com.zhan.jarvis.concurrency.ConcurrencyController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 钩子注册和触发管理器。
 */
public class HookManager {

    public static final String AGENT_PRE_PROCESS = "agent.pre_process";
    public static final String AGENT_POST_PROCESS = "agent.post_process";
    public static final String TOOL_PRE_CALL = "tool.pre_call";
    public static final String TOOL_POST_CALL = "tool.post_call";

    private static final Logger log = LoggerFactory.getLogger(HookManager.class);

    private final Map<String, CopyOnWriteArrayList<Hook>> hooks = new ConcurrentHashMap<>();
    private final ConcurrencyController concurrencyController;

    public HookManager() {
        this(null);
    }

    public HookManager(ConcurrencyController concurrencyController) {
        this.concurrencyController = concurrencyController;
    }

    /** 注册一个钩子到指定事件。 */
    public void register(String eventType, Hook hook) {
        hooks.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(hook);
        log.info("Hook 已注册: eventType={}, hook={}, async={}", eventType, hook.name(), hook.async());
    }

    /** 触发事件。钩子异常不会中断 Agent 主流程。 */
    public void trigger(HookContext ctx) {
        List<Hook> eventHooks = hooks.getOrDefault(ctx.eventType(), new CopyOnWriteArrayList<>());
        if (eventHooks.isEmpty()) {
            return;
        }

        for (var hook : eventHooks) {
            if (hook.async()) {
                executeAsync(hook, ctx);
            } else {
                executeHook(hook, ctx);
            }
        }
    }

    /**
     * 触发策略事件。
     * 同步钩子返回拒绝或抛出策略异常时会阻断主流程；异步钩子只作为旁路观察执行。
     */
    public void triggerPolicy(HookContext ctx) {
        List<Hook> eventHooks = hooks.getOrDefault(ctx.eventType(), new CopyOnWriteArrayList<>());
        if (eventHooks.isEmpty()) {
            return;
        }

        for (var hook : eventHooks) {
            if (hook.async()) {
                executeAsync(hook, ctx);
                continue;
            }
            HookResult result = evaluateHook(hook, ctx);
            if (!result.allowed()) {
                throw new HookDecisionException("Hook " + hook.name() + " denied "
                        + ctx.eventType() + ": " + result.reason());
            }
        }
    }

    private void executeAsync(Hook hook, HookContext ctx) {
        ConcurrencyController.Permit permit = concurrencyController != null
                ? concurrencyController.tryAcquireAsyncHook()
                : null;
        if (concurrencyController != null && permit == null) {
            log.warn("异步 Hook 并发数已达上限，丢弃旁路事件: eventType={}, hook={}",
                    ctx.eventType(), hook.name());
            return;
        }
        try {
            Thread.startVirtualThread(() -> {
                try {
                    executeHook(hook, ctx);
                } finally {
                    if (permit != null) {
                        permit.close();
                    }
                }
            });
        } catch (RuntimeException e) {
            if (permit != null) {
                permit.close();
            }
            log.warn("异步 Hook 启动失败: eventType={}, hook={}, error={}",
                    ctx.eventType(), hook.name(), e.getMessage());
        }
    }

    private void executeHook(Hook hook, HookContext ctx) {
        try {
            hook.evaluate(ctx);
        } catch (Exception e) {
            log.warn("Hook 执行失败: eventType={}, hook={}, error={}",
                    ctx.eventType(), hook.name(), e.getMessage());
            log.debug("Hook 执行失败详情", e);
        }
    }

    private HookResult evaluateHook(Hook hook, HookContext ctx) {
        try {
            return hook.evaluate(ctx);
        } catch (HookDecisionException e) {
            throw e;
        } catch (Exception e) {
            log.warn("策略 Hook 执行失败，拒绝主流程: eventType={}, hook={}, error={}",
                    ctx.eventType(), hook.name(), e.getMessage());
            log.debug("策略 Hook 执行失败详情", e);
            throw new HookDecisionException("Hook " + hook.name() + " failed: " + e.getMessage());
        }
    }
}
