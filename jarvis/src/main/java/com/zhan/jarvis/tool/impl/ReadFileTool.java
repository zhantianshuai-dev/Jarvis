package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.sandbox.SandboxManager;
import com.zhan.jarvis.tool.McpTool;
import com.zhan.jarvis.tool.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * 读取文件内容。
 */
public class ReadFileTool implements McpTool {

    private final ObjectMapper mapper;
    private final SandboxManager sandboxManager;

    public ReadFileTool(ObjectMapper mapper, SandboxManager sandboxManager) {
        this.mapper = mapper;
        this.sandboxManager = sandboxManager;
    }

    @Override public String name() { return "read_file"; }

    @Override public String description() {
        return "读取文件的全部内容，返回字符串。用于查看文件。";
    }

    @Override public JsonNode inputSchema() {
        var schema = mapper.createObjectNode()
                .put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path")
                .put("type", "string")
                .put("description", "文件路径（相对于工作目录或绝对路径）");
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        String relPath = (String) arguments.get("path");
        if (relPath == null || relPath.isBlank()) {
            return "错误: 缺少 path 参数";
        }
        try {
            return sandboxManager.readFile(ctx.effectiveWorkspaceDir(), relPath);
        } catch (IOException e) {
            return "读取文件失败: " + relPath + " — " + e.getMessage();
        }
    }
}
