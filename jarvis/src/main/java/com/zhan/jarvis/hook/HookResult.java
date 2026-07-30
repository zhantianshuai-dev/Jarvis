package com.zhan.jarvis.hook;

/**
 * 钩子执行结果。
 * 观察型钩子返回允许；策略型钩子可返回拒绝来阻断主流程。
 */
public record HookResult(
        boolean allowed,
        String reason
) {
    public static HookResult allow() {
        return new HookResult(true, "");
    }

    public static HookResult deny(String reason) {
        return new HookResult(false, reason == null || reason.isBlank() ? "Hook denied" : reason);
    }
}
