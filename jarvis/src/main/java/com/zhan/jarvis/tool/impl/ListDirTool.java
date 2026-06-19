package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.sandbox.SandboxManager;
import com.zhan.jarvis.tool.McpTool;
import com.zhan.jarvis.tool.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/**
 * 列出目录内容。
 */
public class ListDirTool implements McpTool {

    private final ObjectMapper mapper;
    private final SandboxManager sandboxManager;

    public ListDirTool(ObjectMapper mapper, SandboxManager sandboxManager) {
        this.mapper = mapper;
        this.sandboxManager = sandboxManager;
    }

    @Override public String name() { return "list_dir"; }

    @Override public String description() {
        return "列出目录下的文件和子目录，用于浏览 workspace 文件结构。";
    }

    @Override public JsonNode inputSchema() {
        var schema = mapper.createObjectNode()
                .put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path")
                .put("type", "string")
                .put("description", "目录路径，默认可传 . 表示工作目录");
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        String path = (String) arguments.getOrDefault("path", ".");
        if (path == null || path.isBlank()) {
            path = ".";
        }
        try {
            var items = sandboxManager.listDir(ctx.effectiveWorkspaceDir(), path);
            if (items.isEmpty()) {
                return "目录为空: " + path;
            }
            var sb = new StringBuilder();
            for (var item : items) {
                String marker = Files.isDirectory(item) ? "[dir] " : "[file] ";
                sb.append(marker).append(item.getFileName()).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return "列出目录失败: " + path + " — " + e.getMessage();
        }
    }
}
