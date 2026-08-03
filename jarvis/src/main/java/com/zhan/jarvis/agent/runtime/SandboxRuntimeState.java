package com.zhan.jarvis.agent.runtime;

/**
 * 单次运行绑定的沙箱状态。
 * 第一版只建模，不强制启用沙箱；后续 SandboxMiddleware 会负责填充和释放。
 */
public record SandboxRuntimeState(
        String sandboxId,
        String workspacePath,
        String uploadsPath,
        String outputsPath
) {
    public static SandboxRuntimeState empty(String workspacePath) {
        return new SandboxRuntimeState(null, workspacePath, null, null);
    }
}
