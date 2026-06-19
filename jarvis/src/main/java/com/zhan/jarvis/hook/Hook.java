package com.zhan.jarvis.hook;

/**
 * Agent 生命周期 Hook。
 * 可作为观察型 hook 记录日志/指标，也可作为同步策略 hook 返回 deny 阻断主流程。
 */
public interface Hook {

    /** Hook 名称，用于日志和排查。 */
    String name();

    /** 是否异步执行。异步 hook 不阻塞主流程。 */
    default boolean async() {
        return false;
    }

    /**
     * 执行 hook 并返回策略结果。
     * 默认调用 execute 后允许主流程继续，兼容观察型 hook。
     */
    default HookResult evaluate(HookContext ctx) {
        execute(ctx);
        return HookResult.allow();
    }

    /** 执行观察型 hook。 */
    default void execute(HookContext ctx) {
        // Optional for policy hooks that override evaluate.
    }
}
