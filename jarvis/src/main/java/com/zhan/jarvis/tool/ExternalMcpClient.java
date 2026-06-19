package com.zhan.jarvis.tool;

import com.zhan.jarvis.llm.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 外部 MCP Server Client。
 * <p>
 * 对外暴露的工具名采用 {@code serverName__toolName}，避免与本地工具或其他 MCP Server 冲突。
 */
public class ExternalMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalMcpClient.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpServerConfig config;
    private final McpTransport transport;
    private final ObjectMapper objectMapper;
    private final AtomicLong ids = new AtomicLong(1);
    private volatile boolean available;
    private volatile List<ToolDefinition> cachedTools = List.of();
    private volatile Map<String, String> exposedToRemote = Map.of();

    public ExternalMcpClient(McpServerConfig config, McpTransport transport, ObjectMapper objectMapper) {
        this.config = config;
        this.transport = transport;
        this.objectMapper = objectMapper;
        initialize();
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public List<ToolDefinition> listTools() {
        if (!available) return List.of();
        if (cachedTools.isEmpty()) refreshTools();
        return cachedTools;
    }

    @Override
    public String callTool(String name, Map<String, Object> arguments, ToolContext ctx) {
        if (!available) {
            throw new IllegalStateException("MCP Server 不可用: " + config.name());
        }
        String remoteName = exposedToRemote.getOrDefault(name, name);

        var params = objectMapper.createObjectNode();
        params.put("name", remoteName);
        params.set("arguments", objectMapper.valueToTree(arguments == null ? Map.of() : arguments));

        var response = request("tools/call", params);
        var result = response.path("result");
        if (result.isMissingNode() || result.isNull()) return "";
        return stringifyToolResult(result);
    }

    @Override
    public boolean hasTool(String name) {
        if (!available) return false;
        if (cachedTools.isEmpty()) refreshTools();
        return exposedToRemote.containsKey(name);
    }

    private void initialize() {
        try {
            var params = objectMapper.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.set("capabilities", objectMapper.createObjectNode());
            var clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", "Jarvis");
            clientInfo.put("version", "0.0.1");

            request("initialize", params);
            notifyInitialized();
            refreshTools();
            available = true;
            log.info("外部 MCP Server 已连接: {} tools={}", config.name(), cachedTools.size());
        } catch (Exception e) {
            available = false;
            log.warn("外部 MCP Server 初始化失败: {} ({})", config.name(), e.getMessage());
            log.debug("外部 MCP Server 初始化失败详情", e);
        }
    }

    private void notifyInitialized() {
        var notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        transport.notify(notification);
    }

    private synchronized void refreshTools() {
        try {
            var response = request("tools/list", objectMapper.createObjectNode());
            var tools = response.path("result").path("tools");
            if (!tools.isArray()) {
                throw new IllegalStateException("MCP tools/list 响应缺少 result.tools: " + response);
            }

            var definitions = new ArrayList<ToolDefinition>();
            var mapping = new LinkedHashMap<String, String>();
            for (var tool : tools) {
                String remoteName = tool.path("name").asText();
                if (remoteName == null || remoteName.isBlank()) continue;
                String exposedName = config.name() + "__" + remoteName;
                String description = tool.path("description").asText("");
                Map<String, Object> inputSchema = rawToMap(tool.path("inputSchema"));
                definitions.add(new ToolDefinition(exposedName, description, inputSchema));
                mapping.put(exposedName, remoteName);
            }
            cachedTools = List.copyOf(definitions);
            exposedToRemote = Map.copyOf(mapping);
        } catch (Exception e) {
            available = false;
            log.warn("刷新外部 MCP 工具失败: {} ({})", config.name(), e.getMessage());
            log.debug("刷新外部 MCP 工具失败详情", e);
        }
    }

    private ObjectNode request(String method, ObjectNode params) {
        var request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", ids.getAndIncrement());
        request.put("method", method);
        request.set("params", params);

        var response = transport.send(request);
        if (response.has("error")) {
            throw new RuntimeException("MCP error: " + response.path("error"));
        }
        validateBusinessResponse(method, response);
        return response;
    }

    private void validateBusinessResponse(String method, ObjectNode response) {
        if (response.has("result")) {
            return;
        }

        if (response.has("success") && !response.path("success").asBoolean(true)) {
            throw new RuntimeException("MCP Server 业务错误: " + formatBusinessError(response));
        }

        if (response.has("code") || response.has("msg") || response.has("message")) {
            throw new RuntimeException("MCP Server 非 JSON-RPC 响应: " + formatBusinessError(response));
        }

        throw new RuntimeException("MCP " + method + " 响应缺少 result: " + response);
    }

    private String formatBusinessError(ObjectNode response) {
        var parts = new ArrayList<String>();
        if (response.has("code")) {
            parts.add("code=" + response.path("code").asText());
        }
        if (response.has("msg")) {
            parts.add("msg=" + response.path("msg").asText());
        }
        if (response.has("message")) {
            parts.add("message=" + response.path("message").asText());
        }
        if (parts.isEmpty()) {
            return response.toString();
        }
        return String.join(", ", parts);
    }

    private String stringifyToolResult(JsonNode result) {
        var content = result.path("content");
        if (content.isArray()) {
            var parts = new ArrayList<String>();
            for (var item : content) {
                if ("text".equals(item.path("type").asText()) && item.has("text")) {
                    parts.add(item.path("text").asText());
                } else {
                    parts.add(item.toString());
                }
            }
            return String.join("\n", parts);
        }
        if (result.has("structuredContent")) {
            return result.path("structuredContent").toString();
        }
        if (result.has("content")) {
            return result.path("content").toString();
        }
        return result.toString();
    }

    private Map<String, Object> rawToMap(JsonNode node) {
        if (node == null || !node.isObject()) return Map.of();
        var map = new LinkedHashMap<String, Object>();
        var objNode = (ObjectNode) node;
        for (var entry : objNode.properties()) {
            map.put(entry.getKey(), nodeToValue(entry.getValue()));
        }
        return map;
    }

    private Object nodeToValue(JsonNode node) {
        if (node.isObject()) return rawToMap(node);
        if (node.isArray()) {
            var list = new ArrayList<>();
            for (var item : node) list.add(nodeToValue(item));
            return list;
        }
        if (node.isTextual()) return node.asText();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble() || node.isFloat()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNull()) return null;
        return node.asText();
    }
}
