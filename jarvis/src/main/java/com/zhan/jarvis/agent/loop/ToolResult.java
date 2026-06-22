package com.zhan.jarvis.agent.loop;

/**
 * 单个工具调用的执行结果。
 */
public record ToolResult(
        String toolCallId,
        String toolName,
        String arguments,
        String result
) {
}
