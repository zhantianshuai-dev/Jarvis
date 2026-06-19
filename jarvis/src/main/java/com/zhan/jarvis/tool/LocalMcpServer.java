package com.zhan.jarvis.tool;

import com.zhan.jarvis.llm.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地 MCP Server — 管理内置工具的注册、发现和调用。
 * <p>
 * 对标 MCP 协议的 tools/list 和 tools/call 语义。
 * 内置工具通过此 Server 注册，与外部 MCP 工具使用相同的 ToolDefinition 格式。
 */
public class LocalMcpServer {

    private static final Logger log = LoggerFactory.getLogger(LocalMcpServer.class);

    private final Map<String, McpTool> tools = new ConcurrentHashMap<>();

    /** 注册一个本地工具 */
    public void register(McpTool tool) {
        tools.put(tool.name(), tool);
        log.info("注册本地 MCP 工具: {} — {}", tool.name(), tool.description());
    }

    /** 批量注册 */
    public void registerAll(McpTool... toolList) {
        for (var t : toolList) register(t);
    }

    /**
     * tools/list — 获取所有已注册工具的定义（MCP/OpenAI 兼容格式）。
     */
    public List<ToolDefinition> listTools() {
        return tools.values().stream()
                .map(t -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> schema = rawToMap(t.inputSchema());
                    return new ToolDefinition(t.name(), t.description(), schema);
                })
                .toList();
    }

    /**
     * tools/call — 按名称调用工具。
     *
     * @param name      工具名称
     * @param arguments JSON 反序列化后的参数 Map
     * @param ctx       运行时上下文
     * @return 工具执行结果
     * @throws IllegalArgumentException 工具未注册时抛出
     */
    public String callTool(String name, Map<String, Object> arguments, ToolContext ctx) {
        var tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未注册的工具: " + name);
        }
        log.info("调用工具: {} args={}", name, arguments);
        long start = System.currentTimeMillis();
        String result = tool.execute(arguments, ctx);
        long elapsed = System.currentTimeMillis() - start;
        log.info("工具 {} 执行完成 ({})ms, 结果长度: {} 字符", name, elapsed,
                result != null ? result.length() : 0);
        return result;
    }

    /** 检查工具是否已注册 */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /** 获取已注册工具数量 */
    public int toolCount() {
        return tools.size();
    }

    /** 获取所有工具名称（用于子 Agent 过滤） */
    public Set<String> toolNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * 将 Jackson JsonNode 递归转换为普通 Map<String, Object>。
     * 用于将 inputSchema 转为 ToolDefinition 所需的格式。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> rawToMap(tools.jackson.databind.JsonNode node) {
        if (node == null || !node.isObject()) return Map.of();
        var map = new LinkedHashMap<String, Object>();
        var objNode = (tools.jackson.databind.node.ObjectNode) node;
        for (var entry : objNode.properties()) {
            map.put(entry.getKey(), nodeToValue(entry.getValue()));
        }
        return map;
    }

    private static Object nodeToValue(tools.jackson.databind.JsonNode node) {
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