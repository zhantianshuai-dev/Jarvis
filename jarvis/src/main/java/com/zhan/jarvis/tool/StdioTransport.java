package com.zhan.jarvis.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP stdio 传输。
 * <p>
 * 每个 JSON-RPC 请求以一行 JSON 写入子进程 stdin，从 stdout 读取同 id 响应。
 */
public class StdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String serverName;
    private final ObjectMapper objectMapper;
    private final Process process;
    private final BufferedWriter stdin;
    private final BufferedReader stdout;

    public StdioTransport(McpServerConfig config, ObjectMapper objectMapper) {
        if (config.command() == null || config.command().isBlank()) {
            throw new IllegalArgumentException("stdio MCP Server 缺少 command: " + config.name());
        }
        this.serverName = config.name();
        this.objectMapper = objectMapper;

        try {
            var command = new ArrayList<String>();
            command.add(config.command());
            command.addAll(config.args());

            var builder = new ProcessBuilder(command);
            this.process = builder.start();
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            drainStderr(process.getErrorStream());
            log.info("已启动 stdio MCP Server: {} command={}", serverName, command);
        } catch (IOException e) {
            throw new RuntimeException("启动 stdio MCP Server 失败: " + config.name(), e);
        }
    }

    @Override
    public synchronized ObjectNode send(ObjectNode request) {
        ensureAlive();
        String id = request.path("id").asText();
        try {
            stdin.write(objectMapper.writeValueAsString(request));
            stdin.newLine();
            stdin.flush();

            long deadline = System.nanoTime() + DEFAULT_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (!stdout.ready()) {
                    if (!process.isAlive()) {
                        throw new IllegalStateException("stdio MCP Server 已退出: " + serverName);
                    }
                    Thread.sleep(20);
                    continue;
                }

                String line = stdout.readLine();
                if (line == null) break;
                if (line.isBlank()) continue;

                var node = objectMapper.readTree(line);
                if (!node.isObject()) continue;
                var response = (ObjectNode) node;
                if (id.equals(response.path("id").asText())) {
                    return response;
                }
                log.debug("忽略 MCP stdio 非目标消息: server={}, message={}", serverName, line);
            }
            throw new RuntimeException("等待 stdio MCP 响应超时: " + serverName + " id=" + id);
        } catch (Exception e) {
            throw new RuntimeException("stdio MCP 请求失败: " + serverName, e);
        }
    }

    @Override
    public synchronized void notify(ObjectNode notification) {
        ensureAlive();
        try {
            stdin.write(objectMapper.writeValueAsString(notification));
            stdin.newLine();
            stdin.flush();
        } catch (IOException e) {
            throw new RuntimeException("stdio MCP notification 发送失败: " + serverName, e);
        }
    }

    @Override
    public void close() {
        process.destroy();
    }

    private void ensureAlive() {
        if (!process.isAlive()) {
            throw new IllegalStateException("stdio MCP Server 不可用: " + serverName);
        }
    }

    private void drainStderr(InputStream stderr) {
        Thread.startVirtualThread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[mcp:{} stderr] {}", serverName, line);
                }
            } catch (IOException ignored) {
                // Process teardown.
            }
        });
    }
}
