package com.zhan.jarvis.hook;

/**
 * Agent 生命周期钩子。
 * 可作为观察型钩子记录日志/指标，也可作为同步策略钩子返回拒绝来阻断主流程。
 */
public interface Hook {

    /** 钩子名称，用于日志和排查。 */
    String name();

    /** 是否异步执行。异步钩子不阻塞主流程。 */
    default boolean async() {
        return false;
    }

    /**
     * 执行钩子并返回策略结果。
     * 默认调用执行方法后允许主流程继续，兼容观察型钩子。
     */
    default HookResult evaluate(HookContext ctx) {
        execute(ctx);
        return HookResult.allow();
    }

    /** 执行观察型钩子。 */
    default void execute(HookContext ctx) {
        // 覆盖评估方法的策略型钩子可以不实现该方法。
    }
}
