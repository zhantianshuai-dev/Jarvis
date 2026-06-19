package com.zhan.jarvis.tool;

import tools.jackson.databind.node.ObjectNode;

/**
 * MCP JSON-RPC 传输层。
 * <p>
 * 实现类负责把 JSON-RPC 请求发送到外部 MCP Server，并返回 JSON-RPC 响应。
 */
public interface McpTransport extends AutoCloseable {

    /** 发送有 id 的 JSON-RPC 请求并等待响应。 */
    ObjectNode send(ObjectNode request);

    /** 发送无 id 的 JSON-RPC notification。 */
    void notify(ObjectNode notification);

    @Override
    void close();
}
