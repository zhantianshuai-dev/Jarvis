package com.zhan.jarvis.llm;

import java.util.List;

/**
 * 对话消息。
 *
 * @param role             system / user / assistant / tool
 * @param content          文本内容（tool 角色时为工具执行结果）
 * @param toolCallId       当 role=tool 时，关联的 tool_call id
 * @param toolCalls        当 role=assistant 且调用了工具时，非空
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