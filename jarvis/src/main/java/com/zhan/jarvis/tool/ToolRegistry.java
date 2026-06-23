package com.zhan.jarvis.tool;

import com.zhan.jarvis.agent.RunMode;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具注册表 — 聚合本地工具 + 外部 MCP 工具，提供统一入口。
 * <p>
 * AgentLoop 通过此注册表获取可用工具列表和调用工具，无需关心工具来源。
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    public static final String TOOL_SEARCH = "tool_search";

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
     * 获取所有可用工具的定义列表。内部调用和子 Agent 默认仍可拿到完整集合。
     */
    public List<ToolDefinition> listTools() {
        var tools = new ArrayList<ToolDefinition>();
        for (var tool : localServer.listTools()) {
            tools.add(withMetadata(tool, "local"));
        }
        //外部mcp工具
        for (var client : externalClients) {
            if (client.isAvailable()) {
                for (var tool : client.listTools()) {
                    tools.add(withMetadata(tool, "external_mcp"));
                }
            }
        }
        log.debug("listTools: 返回 {} 个内部工具定义", tools.size());
        return tools;
    }

    /**
     * 按运行模式裁剪工具 schema，降低每轮 LLM 请求的固定 token 成本。
     * 大工具和外部 MCP 默认延迟暴露，LLM 需要时先调用 tool_search。
     */
    public List<ToolDefinition> listToolsForMode(RunMode mode, String userMessage, Set<String> activeDeferredTools) {
        RunMode runMode = mode != null ? mode : RunMode.AGENT;
        var active = activeDeferredTools != null ? activeDeferredTools : Set.<String>of();
        var allTools = listTools();
        if (runMode == RunMode.CHAT) {
            return allTools.stream()
                    .filter(tool -> "memory".equals(tool.group()) && isMemoryIntent(userMessage))
                    .filter(tool -> !tool.deferred() || active.contains(tool.name()))
                    .toList();
        }

        var selectedGroups = selectedGroups(runMode, userMessage);
        var visible = new ArrayList<ToolDefinition>();
        var hidden = new ArrayList<ToolDefinition>();
        for (var tool : allTools) {
            if (TOOL_SEARCH.equals(tool.name())) {
                continue;
            }
            boolean selected = selectedGroups.contains(tool.group())
                    || selectedGroups.contains(tool.name())
                    || active.contains(tool.name())
                    || (runMode == RunMode.SUPER_AGENT && !tool.deferred());
            if (!selected) {
                hidden.add(tool);
                continue;
            }
            if (tool.deferred() && !active.contains(tool.name())) {
                hidden.add(tool);
                continue;
            }
            visible.add(tool);
        }
        if (!hidden.isEmpty()) {
            visible.add(toolSearchDefinition());
        }
        log.debug("listToolsForMode: mode={}, visible={}, hidden={}", runMode.value(), visible.size(), hidden.size());
        return visible;
    }

    /**
     * 返回当前模式下可通过 tool_search 延迟加载的工具目录。
     * 只给系统提示词使用，不包含完整 schema。
     */
    public List<ToolDefinition> availableDeferredToolsForMode(RunMode mode, String userMessage) {
        RunMode runMode = mode != null ? mode : RunMode.AGENT;
        if (runMode == RunMode.CHAT) {
            return List.of();
        }
        var selectedGroups = selectedGroups(runMode, userMessage);
        return listTools().stream()
                .filter(ToolDefinition::deferred)
                .filter(tool -> runMode == RunMode.SUPER_AGENT
                        || selectedGroups.contains(tool.group())
                        || selectedGroups.contains(tool.name())
                        || "mcp".equals(tool.group()))
                .toList();
    }

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
            if (TOOL_SEARCH.equals(name)) {
                result = executeToolSearch(arguments);
            } else if (localServer.hasTool(name)) {
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

    private String executeToolSearch(Map<String, Object> arguments) {
        String query = String.valueOf(arguments == null ? "" : arguments.getOrDefault("query", "")).toLowerCase();
        String group = String.valueOf(arguments == null ? "" : arguments.getOrDefault("group", "")).toLowerCase();
        var candidates = listTools().stream()
                .filter(ToolDefinition::deferred)
                .filter(tool -> matchesToolSearch(tool, query, group))
                .limit(8)
                .toList();
        var promoted = candidates.stream().map(ToolDefinition::name).toList();
        var response = new LinkedHashMap<String, Object>();
        response.put("deferred_tool_search", true);
        response.put("promoted_tools", promoted);
        response.put("message", promoted.isEmpty()
                ? "没有匹配到可启用的延迟工具。请查看 <available-deferred-tools> 后使用 select:<tool_name>。"
                : String.join(", ", promoted) + " 工具已启用；完整 schema 会出现在下一轮 request 的 tools 字段中。");
        response.put("matched_tools", candidates.stream()
                .map(tool -> Map.of(
                        "name", tool.name(),
                        "group", tool.group(),
                        "description", shortText(safeText(tool.description()), 120)
                ))
                .toList());
        return toJson(response);
    }

    private boolean matchesToolSearch(ToolDefinition tool, String query, String group) {
        if (query.startsWith("select:")) {
            String selected = query.substring("select:".length());
            for (String name : selected.split(",")) {
                if (tool.name().equalsIgnoreCase(name.strip())) {
                    return true;
                }
            }
            return false;
        }
        boolean groupMatched = group.isBlank()
                || tool.group().toLowerCase().contains(group)
                || tool.name().toLowerCase().contains(group);
        if (!groupMatched) {
            return false;
        }
        if (query.isBlank() || !group.isBlank()) {
            return true;
        }

        String haystack = (tool.name() + " " + tool.group() + " " + safeText(tool.description())).toLowerCase();
        for (String token : query.split("\\s+")) {
            if (!token.isBlank() && haystack.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private ToolDefinition withMetadata(ToolDefinition tool, String source) {
        String group = groupFor(tool.name(), source);
        return new ToolDefinition(
                tool.name(),
                tool.description(),
                tool.inputSchema(),
                group,
                source,
                schemaWeightFor(tool.name(), source),
                deferredFor(tool.name(), source, group)
        );
    }

    private ToolDefinition toolSearchDefinition() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "查询延迟工具。优先使用 select:<tool_name> 精确加载；也可用关键词搜索。工具名见 <available-deferred-tools>。"
        ));
        properties.put("group", Map.of(
                "type", "string",
                "enum", List.of("git", "cron", "feishu", "image", "subagent", "mcp"),
                "description", "可选，必须使用已有工具组之一。"
        ));
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                TOOL_SEARCH,
                "启用延迟工具。先查看 <available-deferred-tools>，优先用 select:<tool_name>；完整 schema 只会在下一轮 tools 字段中提供。",
                schema,
                "router",
                "local",
                "small",
                false
        );
    }

    private Set<String> selectedGroups(RunMode mode, String message) {
        var groups = new LinkedHashSet<String>();
        String text = message == null ? "" : message.toLowerCase();
        groups.add("memory");
        groups.add("web");

        if (mode == RunMode.SUPER_AGENT) {
            groups.addAll(List.of("file", "exec", "git", "cron", "feishu", "image", "subagent", "mcp"));
            return groups;
        }

        if (containsAny(text, "文件", "读取", "写入", "编辑", "目录", "代码", "file", "read", "write", "edit")) {
            groups.add("file");
            groups.add("exec");
        }
        if (containsAny(text, "git", "commit", "push", "branch", "分支", "提交", "暂存", "状态")) {
            groups.add("git");
        }
        if (containsAny(text, "飞书", "群聊", "历史消息", "消息记录", "feishu", "lark")) {
            groups.add("feishu");
        }
        if (containsAny(text, "图片", "画", "生成图", "image", "photo")) {
            groups.add("image");
        }
        if (containsAny(text, "定时", "提醒", "cron", "schedule")) {
            groups.add("cron");
        }
        if (containsAny(text, "子agent", "子 agent", "并行", "拆分任务", "spawn")) {
            groups.add("subagent");
            groups.add("file");
        }
        if (containsAny(text, "搜索", "联网", "最新", "web search", "mcp")) {
            groups.add("mcp");
        }
        return groups;
    }

    private String groupFor(String name, String source) {
        if ("external_mcp".equals(source)) {
            return "mcp";
        }
        return switch (name) {
            case "read_file", "write_file", "edit_file", "list_dir" -> "file";
            case "exec" -> "exec";
            case "git" -> "git";
            case "cron" -> "cron";
            case "feishu_history_messages" -> "feishu";
            case "imagegen" -> "image";
            case "memory_search", "memory_remember", "memory_commit" -> "memory";
            case "web_fetch" -> "web";
            case "spawn" -> "subagent";
            default -> "general";
        };
    }

    private String schemaWeightFor(String name, String source) {
        if ("external_mcp".equals(source) || Set.of("git", "imagegen", "cron", "feishu_history_messages").contains(name)) {
            return "large";
        }
        return "medium";
    }

    private boolean deferredFor(String name, String source, String group) {
        if ("external_mcp".equals(source)) {
            return true;
        }
        return Set.of("git", "cron", "feishu", "image", "subagent").contains(group);
    }

    private boolean isMemoryIntent(String message) {
        String text = message == null ? "" : message.toLowerCase();
        return containsAny(text, "记住", "记忆", "回忆", "remember", "memory");
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private String shortText(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxLength)) + "...";
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
