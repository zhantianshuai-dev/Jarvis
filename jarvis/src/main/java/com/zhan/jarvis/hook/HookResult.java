package com.zhan.jarvis.hook;

/**
 * Hook 执行结果。
 * 观察型 hook 返回 allow；策略型 hook 可返回 deny 阻断主流程。
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
