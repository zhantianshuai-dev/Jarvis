package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.memory.MemoryServiceClient;
import com.zhan.jarvis.tool.McpTool;
import com.zhan.jarvis.tool.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 触发当前会话的记忆巩固。
 * 调用 memory-service /api/v1/session/commit。
 */
public class MemoryCommitTool implements McpTool {

    private static final int DEFAULT_KEEP_RECENT_COUNT = 20;

    private final ObjectMapper mapper;
    private final MemoryServiceClient memoryClient;

    public MemoryCommitTool(ObjectMapper mapper, MemoryServiceClient memoryClient) {
        this.mapper = mapper;
        this.memoryClient = memoryClient;
    }

    @Override public String name() { return "memory_commit"; }

    @Override public String description() {
        return "触发当前会话的记忆巩固。用于用户明确要求总结、归档或记住当前对话时。" +
               "该工具会让 memory-service 归档旧消息、生成 working memory，并在后台提取长期记忆。";
    }

    @Override public JsonNode inputSchema() {
        var schema = mapper.createObjectNode()
                .put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("keep_recent_count")
                .put("type", "integer")
                .put("description", "提交后保留最近多少条消息，默认 20");
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        int keepRecentCount = DEFAULT_KEEP_RECENT_COUNT;
        Object keepObj = arguments.get("keep_recent_count");
        if (keepObj instanceof Number n) {
            keepRecentCount = n.intValue();
        }
        if (keepRecentCount < 0) {
            return "错误: keep_recent_count 不能小于 0";
        }

        try {
            String result = memoryClient.commitSession(ctx.sessionId(), keepRecentCount);
            return "当前会话记忆巩固已触发: " + result;
        } catch (Exception e) {
            return "记忆巩固触发失败: " + e.getMessage();
        }
    }
}
