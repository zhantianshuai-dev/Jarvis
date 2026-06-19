package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.sandbox.SandboxManager;
import com.zhan.jarvis.tool.McpTool;
import com.zhan.jarvis.tool.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
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
            return "文件已写入: " + filePath + " (" + content.length() + " 字符)";
        } catch (IOException e) {
            return "写入文件失败: " + relPath + " — " + e.getMessage();
        }
    }
}
