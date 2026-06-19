package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.sandbox.SandboxManager;
import com.zhan.jarvis.tool.McpTool;
import com.zhan.jarvis.tool.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * 精确替换文件中的一段文本。
 */
public class EditFileTool implements McpTool {

    private final ObjectMapper mapper;
    private final SandboxManager sandboxManager;

    public EditFileTool(ObjectMapper mapper, SandboxManager sandboxManager) {
        this.mapper = mapper;
        this.sandboxManager = sandboxManager;
    }

    @Override public String name() { return "edit_file"; }

    @Override public String description() {
        return "通过精确匹配 old_text 并替换为 new_text 来编辑文件。old_text 必须在文件中唯一出现。";
    }

    @Override public JsonNode inputSchema() {
        var schema = mapper.createObjectNode()
                .put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path")
                .put("type", "string")
                .put("description", "要编辑的文件路径");
        props.putObject("old_text")
                .put("type", "string")
                .put("description", "要查找的原始文本，必须与文件内容精确一致");
        props.putObject("new_text")
                .put("type", "string")
                .put("description", "替换后的新文本");
        schema.putArray("required").add("path").add("old_text").add("new_text");
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        String path = (String) arguments.get("path");
        String oldText = (String) arguments.get("old_text");
        String newText = (String) arguments.get("new_text");
        if (path == null || path.isBlank()) {
            return "错误: 缺少 path 参数";
        }
        if (oldText == null || oldText.isEmpty()) {
            return "错误: 缺少 old_text 参数";
        }
        if (newText == null) {
            return "错误: 缺少 new_text 参数";
        }

        try {
            String content = sandboxManager.readFile(ctx.effectiveWorkspaceDir(), path);
            int first = content.indexOf(oldText);
            if (first < 0) {
                return "编辑失败: old_text 未在文件中找到，请读取文件后提供精确片段";
            }
            int second = content.indexOf(oldText, first + oldText.length());
            if (second >= 0) {
                return "编辑失败: old_text 在文件中出现多次，请提供更长上下文使其唯一";
            }

            String updated = content.substring(0, first) + newText + content.substring(first + oldText.length());
            var filePath = sandboxManager.writeFile(ctx.effectiveWorkspaceDir(), path, updated);
            return "文件已编辑: " + filePath + " (替换 " + oldText.length() + " 字符为 "
                    + newText.length() + " 字符)";
        } catch (IOException e) {
            return "编辑文件失败: " + path + " — " + e.getMessage();
        }
    }
}
