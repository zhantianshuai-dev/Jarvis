package com.zhan.jarvis.tool;

import com.zhan.jarvis.channel.SessionKey;

import java.nio.file.Path;
import java.util.Map;

/**
 * 工具执行时的运行时上下文。
 *
 * @param sessionId    当前会话 ID
 * @param sessionKey   当前消息来源的完整 Channel 会话标识
 * @param workspaceDir Agent 工作目录
 * @param userId       用户标识
 * @param metadata     当前 inbound 消息携带的通道元数据
 */
public record ToolContext(
    String sessionId,
    SessionKey sessionKey,
    String workspaceDir,
    String userId,
    Map<String, Object> metadata
) {
    public ToolContext(String sessionId, SessionKey sessionKey, String workspaceDir, String userId) {
        this(sessionId, sessionKey, workspaceDir, userId, Map.of());
    }

    public ToolContext(String sessionId, String workspaceDir, String userId) {
        this(sessionId, new SessionKey("http", "default", sessionId), workspaceDir, userId, Map.of());
    }

    public ToolContext {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 实际执行工具时使用的工作目录。
     * 默认等于 workspaceDir；当消息 metadata 中带有 worktree_path 时，工具会落到该 worktree。
     */
    public String effectiveWorkspaceDir() {
        Path workspace = Path.of(workspaceDir).toAbsolutePath().normalize();
        Object raw = metadata.get("worktree_path");
        // 如果没有worktree的路径，就回退到默认的工作目录下
        if (raw == null || String.valueOf(raw).isBlank()) {
            return workspace.toString();
        }

        Path candidate = Path.of(String.valueOf(raw));
        Path resolved = candidate.isAbsolute()
                ? candidate.toAbsolutePath().normalize()
                : workspace.resolve(candidate).normalize();
        if (!resolved.startsWith(workspace)) {
            return workspace.toString();
        }
        //返回对应的worktree
        return resolved.toString();
    }
}
