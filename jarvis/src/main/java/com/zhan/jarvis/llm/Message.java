package com.zhan.jarvis.llm;

import java.util.List;

/**
 * 对话消息。
 *
 * @param role             消息角色，如系统/用户/助手/工具
 * @param content          文本内容（工具角色时为工具执行结果）
 * @param toolCallId       当角色为工具时，关联的工具调用 ID
 * @param toolCalls        当角色为助手且调用了工具时，非空
 * @param reasoningContent thinking 模式下的推理内容，需在后续请求中原样传回
 */
public record Message(
    String role,
    String content,
    String toolCallId,
    List<ToolCall> toolCalls,
    String reasoningContent
) {
    public static Message system(String content) {
        return new Message("system", content, null, null, null);
    }

    public static Message user(String content) {
        return new Message("user", content, null, null, null);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content, null, null, null);
    }

    public static Message assistant(List<ToolCall> toolCalls) {
        return new Message("assistant", null, null, toolCalls, null);
    }

    public static Message assistant(List<ToolCall> toolCalls, String reasoningContent) {
        return new Message("assistant", null, null, toolCalls, reasoningContent);
    }

    public static Message tool(String toolCallId, String result) {
        return new Message("tool", result, toolCallId, null, null);
    }
}
