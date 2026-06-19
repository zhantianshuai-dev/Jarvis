package com.zhan.jarvis.tool;

import java.util.List;
import java.util.Map;

/**
 * 外部 MCP Server 配置。
 */
public record McpServerConfig(
        String name,
        String transport,
        String command,
        List<String> args,
        String url,
        Map<String, String> headers
) {

    public static McpServerConfig from(com.zhan.jarvis.config.JarvisConfig.McpConfig.ExternalMcpServer config) {
        return new McpServerConfig(
                config.name(),
                config.transport(),
                config.command(),
                config.args() == null ? List.of() : List.of(config.args()),
                config.url(),
                config.headers() == null ? Map.of() : Map.copyOf(config.headers())
        );
    }
}
