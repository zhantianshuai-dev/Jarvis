package com.zhan.jarvis.tool;

import com.zhan.jarvis.hook.HookContext;
import com.zhan.jarvis.hook.HookDecisionException;
import com.zhan.jarvis.hook.HookManager;
import com.zhan.jarvis.llm.ToolDefinition;
import com.zhan.jarvis.permission.ToolPermissionDecision;
import com.zhan.jarvis.permission.ToolPermissionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表 — 聚合本地工具 + 外部 MCP 工具，提供统一入口。
 * <p>
 * AgentLoop 通过此注册表获取可用工具列表和调用工具，无需关心工具来源。
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final LocalMcpServer localServer;
    private final List<McpClient> externalClients;  // 阶段 2 接入
    private final HookManager hookManager;
    private final ToolPermissionManager permissionManager;
    private final ObjectMapper objectMapper;

    public ToolRegistry(LocalMcpServer localServer, List<McpClient> externalClients) {
        this(localServer, externalClients, null, null, null);
    }

    public ToolRegistry(LocalMcpServer localServer, List<McpClient> externalClients, HookManager hookManager) {
        this(localServer, externalClients, hookManager, null, null);
    }

    public ToolRegistry(LocalMcpServer localServer, List<McpClient> externalClients, HookManager hookManager,
                        ToolPermissionManager permissionManager, ObjectMapper objectMapper) {
        this.localServer = localServer;
        this.externalClients = externalClients != null ? externalClients : List.of();
        this.hookManager = hookManager;
        this.permissionManager = permissionManager;
        this.objectMapper = objectMapper;
        log.info("ToolRegistry 初始化: {} 个本地工具, {} 个外部 MCP Client",
                localServer.toolCount(), this.externalClients.size());
    }



    /**
     * 获取所有本地可用工具的定义列表（用于传给 LLM）。
     */
    public List<ToolDefinition> listTools() {
        //内部工具tool
        var tools = new ArrayList<>(localServer.listTools());
        //外部mcp工具
        for (var client : externalClients) {
            if (client.isAvailable()) {
                tools.addAll(client.listTools());
            }
        }
        log.debug("listTools: 返回 {} 个内部工具定义", tools.size());
        return tools;
    }

    /**
    * 获取所有外部可用工具的定义列表（用于传给 LLM）。
    */
//    public List<ToolDefinition> listExternalTools(){
//        List<ToolDefinition> externalTools = new ArrayList<>();
//        for (var client : externalClients) {
//            if (client.isAvailable()) {
//                externalTools.addAll(client.listTools());
//            }
//        }
//        log.debug("listTools: 返回 {} 个外部工具定义", externalTools.size());
//        return externalTools;
//    }

    /**
     * 执行工具调用。
     * 先在本地查找，再查找外部 MCP 工具。
     *
     * @param name      工具名称
     * @param arguments 参数
     * @param ctx       运行时上下文
     * @return 执行结果
     */
    public String executeTool(String name, Map<String, Object> arguments, ToolContext ctx) {
        //这里触发工具事件
        var prePayload = new java.util.LinkedHashMap<String, Object>();
        prePayload.put("tool_name", name);
        prePayload.put("arguments", arguments == null ? Map.of() : arguments);
        prePayload.put("channel_type", ctx.sessionKey() != null ? ctx.sessionKey().channelType() : "");
        prePayload.put("metadata", ctx.metadata() == null ? Map.of() : ctx.metadata());
        //触发Hook
        triggerPolicyHook(HookManager.TOOL_PRE_CALL, ctx, prePayload);

        long start = System.currentTimeMillis();
        try {
            //这里触发人工确认校验
            ToolPermissionDecision decision = evaluatePermission(name, arguments, ctx);
            if (decision.behavior() == ToolPermissionDecision.Behavior.DENY) {
                throw new HookDecisionException("Tool permission denied: " + decision.reason());
            }
            //这里检查到需要ASK用户，直接返回给AgentLoop
            if (decision.behavior() == ToolPermissionDecision.Behavior.ASK) {
                String result = toJson(decision.payload());
                triggerHook(HookManager.TOOL_POST_CALL, ctx, Map.of(
                        "tool_name", name,
                        "elapsed_ms", System.currentTimeMillis() - start,
                        "result_length", result.length(),
                        "success", true,
                        "requires_confirmation", true
                ));
                return result;
            }

            String result;
            // 本地工具优先
            if (localServer.hasTool(name)) {
                result = localServer.callTool(name, arguments, ctx);
            } else {
                result = null;
                // 外部 MCP 工具
                for (var client : externalClients) {
                    if (client.isAvailable() && client.hasTool(name)) {
                        result = client.callTool(name, arguments, ctx);
                        break;
                    }
                }
            }

            if (result == null) {
                throw new IllegalArgumentException("未找到工具: " + name);
            }
            //工具执行成功后触发事件
            triggerHook(HookManager.TOOL_POST_CALL, ctx, Map.of(
                    "tool_name", name,
                    "elapsed_ms", System.currentTimeMillis() - start,
                    "result_length", result.length(),
                    "success", true
            ));
            return result;
        } catch (RuntimeException e) {
            //工具异常也触发
            triggerHook(HookManager.TOOL_POST_CALL, ctx, Map.of(
                    "tool_name", name,
                    "elapsed_ms", System.currentTimeMillis() - start,
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
            ));
            throw e;
        }
    }

    /**
     * 获取过滤后的工具定义（排除指定名称的工具）。
     * 用于子 Agent 限制工具集（如排除 spawn 工具防止递归）。
     */
    public List<ToolDefinition> listToolsExcept(String... excludeNames) {
        var excludeSet = java.util.Set.of(excludeNames);
        return listTools().stream()
                .filter(td -> !excludeSet.contains(td.name()))
                .toList();
    }

    /** 获取本地 Server（供子 Agent 创建受限注册表） */
    public LocalMcpServer localServer() {
        return localServer;
    }

    private void triggerHook(String eventType, ToolContext ctx, Map<String, Object> payload) {
        if (hookManager == null) {
            return;
        }
        hookManager.trigger(HookContext.of(eventType, ctx.sessionId(), ctx.userId(), payload));
    }

    private void triggerPolicyHook(String eventType, ToolContext ctx, Map<String, Object> payload) {
        if (hookManager == null) {
            return;
        }
        hookManager.triggerPolicy(HookContext.of(eventType, ctx.sessionId(), ctx.userId(), payload));
    }

    private ToolPermissionDecision evaluatePermission(String name, Map<String, Object> arguments, ToolContext ctx) {
        if (permissionManager == null) {
            return ToolPermissionDecision.allow();
        }
        return permissionManager.evaluate(name, arguments == null ? Map.of() : arguments, ctx);
    }

    private String toJson(Object value) {
        if (objectMapper == null) {
            return String.valueOf(value);
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"JSON 序列化失败\"}";
        }
    }
}
