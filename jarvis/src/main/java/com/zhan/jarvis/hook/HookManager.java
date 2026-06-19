package com.zhan.jarvis.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hook 注册和触发管理器。
 */
public class HookManager {

    public static final String AGENT_PRE_PROCESS = "agent.pre_process";
    public static final String AGENT_POST_PROCESS = "agent.post_process";
    public static final String TOOL_PRE_CALL = "tool.pre_call";
    public static final String TOOL_POST_CALL = "tool.post_call";

    private static final Logger log = LoggerFactory.getLogger(HookManager.class);

    private final Map<String, CopyOnWriteArrayList<Hook>> hooks = new ConcurrentHashMap<>();

    /** 注册一个 hook 到指定事件。 */
    public void register(String eventType, Hook hook) {
        hooks.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(hook);
        log.info("Hook 已注册: eventType={}, hook={}, async={}", eventType, hook.name(), hook.async());
    }

    /** 触发事件。Hook 异常不会中断 Agent 主流程。 */
    public void trigger(HookContext ctx) {
        List<Hook> eventHooks = hooks.getOrDefault(ctx.eventType(), new CopyOnWriteArrayList<>());
        if (eventHooks.isEmpty()) {
            return;
        }

        for (var hook : eventHooks) {
            if (hook.async()) {
                Thread.startVirtualThread(() -> executeHook(hook, ctx));
            } else {
                executeHook(hook, ctx);
            }
        }
    }

    /**
     * 触发策略事件。
     * 同步 hook 返回 deny 或抛出 HookDecisionException 时会阻断主流程；异步 hook 只作为旁路观察执行。
     */
    public void triggerPolicy(HookContext ctx) {
        List<Hook> eventHooks = hooks.getOrDefault(ctx.eventType(), new CopyOnWriteArrayList<>());
        if (eventHooks.isEmpty()) {
            return;
        }

        for (var hook : eventHooks) {
            if (hook.async()) {
                Thread.startVirtualThread(() -> executeHook(hook, ctx));
                continue;
            }
            HookResult result = evaluateHook(hook, ctx);
            if (!result.allowed()) {
                throw new HookDecisionException("Hook " + hook.name() + " denied "
                        + ctx.eventType() + ": " + result.reason());
            }
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
