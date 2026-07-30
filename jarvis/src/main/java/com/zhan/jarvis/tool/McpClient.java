package com.zhan.jarvis.tool;

import com.zhan.jarvis.llm.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * MCP 客户端 — 连接外部 MCP 服务。
 * <p>
 * 阶段 2 实现具体传输层（StdioTransport、SseTransport）。
 * 当前为占位实现，允许工具注册表编译通过。
 */
public interface McpClient {

    /** 是否已连接且可用 */
    boolean isAvailable();

    /** 获取该 MCP 服务提供的所有工具定义。 */
    List<ToolDefinition> listTools();

    /** 调用工具 */
    String callTool(String name, Map<String, Object> arguments, ToolContext ctx);

    /** 检查是否提供指定工具 */
    boolean hasTool(String name);
}
