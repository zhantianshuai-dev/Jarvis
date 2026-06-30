package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.sandbox.SandboxManager;
import com.zhan.jarvis.tool.McpTool;
import com.zhan.jarvis.tool.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写入文件内容（创建或覆盖）。
 */
public class WriteFileTool implements McpTool {

    private final ObjectMapper mapper;
    private final SandboxManager sandboxManager;

    public WriteFileTool(ObjectMapper mapper, SandboxManager sandboxManager) {
        this.mapper = mapper;
        this.sandboxManager = sandboxManager;
    }

    @Override public String name() { return "write_file"; }

    @Override public String description() {
        return "将内容写入文件（创建新文件或覆盖已有文件）。用于保存代码、文档等。";
    }

    @Override public JsonNode inputSchema() {
        var schema = mapper.createObjectNode()
                .put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path")
                .put("type", "string")
                .put("description", "文件路径");
        props.putObject("content")
                .put("type", "string")
                .put("description", "要写入的文件内容");
        schema.putArray("required").add("path").add("content");
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        String relPath = (String) arguments.get("path");
        String content = (String) arguments.get("content");
        if (relPath == null || relPath.isBlank()) {
            return "错误: 缺少 path 参数";
        }
        if (content == null) {
            return "错误: 缺少 content 参数";
        }
        try {
            var filePath = sandboxManager.writeFile(ctx.effectiveWorkspaceDir(), relPath, content);
            var result = new LinkedHashMap<String, Object>();
            result.put("tool", name());
            result.put("path", filePath.toString());
            result.put("operation", "write");
            result.put("content_chars", content.length());
            result.put("content_omitted", true);
            result.put("summary", "文件已写入，内容未展开。需要查看内容时请调用 read_file。");
            return toJson(result);
        } catch (IOException e) {
            return toJson(Map.of(
                    "tool", name(),
                    "path", relPath,
                    "success", false,
                    "error", "写入文件失败: " + e.getMessage()
            ));
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"tool\":\"write_file\",\"success\":false,\"error\":\"JSON 序列化失败\"}";
        }
    }
}
