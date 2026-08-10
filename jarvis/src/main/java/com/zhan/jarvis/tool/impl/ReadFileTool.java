package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.agent.loop.ToolResultStore;
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
    private final ToolResultStore toolResultStore;

    public ReadFileTool(ObjectMapper mapper, SandboxManager sandboxManager) {
        this(mapper, sandboxManager, null);
    }

    public ReadFileTool(ObjectMapper mapper, SandboxManager sandboxManager, ToolResultStore toolResultStore) {
        this.mapper = mapper;
        this.sandboxManager = sandboxManager;
        this.toolResultStore = toolResultStore;
    }

    @Override public String name() { return "read_file"; }

    @Override public String description() {
        return "读取文件内容。大文件或工具结果应指定 start_line/end_line 分页读取。";
    }

    @Override public JsonNode inputSchema() {
        var schema = mapper.createObjectNode()
                .put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path")
                .put("type", "string")
                .put("description", "文件路径（相对于工作目录或绝对路径），也支持工具结果虚拟路径 /mnt/user-data/outputs/.tool-results/... ");
        props.putObject("start_line")
                .put("type", "integer")
                .put("minimum", 1)
                .put("description", "可选，起始行号，默认为 1");
        props.putObject("end_line")
                .put("type", "integer")
                .put("minimum", 1)
                .put("description", "可选，结束行号；未设置时最多读取 500 行");
        props.putObject("max_chars")
                .put("type", "integer")
                .put("minimum", 1)
                .put("description", "可选，最大返回字符数，默认最多 12000");
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
            int startLine = positiveInt(arguments.get("start_line"), 1);
            int endLine = positiveInt(arguments.get("end_line"), 0);
            int maxChars = positiveInt(arguments.get("max_chars"), 12_000);
            if (toolResultStore != null && toolResultStore.isVirtualPath(relPath)) {
                return toolResultStore.read(ctx.sessionId(), relPath, startLine, endLine, maxChars);
            }
            return limitText(sandboxManager.readFile(ctx.effectiveWorkspaceDir(), relPath), startLine, endLine, maxChars);
        } catch (IOException e) {
            return "读取文件失败: " + relPath + " — " + e.getMessage();
        }
    }

    private int positiveInt(Object value, int fallback) {
        if (value instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String limitText(String content, int startLine, int endLine, int maxChars) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String[] lines = content.split("\\R", -1);
        int from = Math.max(1, startLine);
        int to = endLine >= from ? endLine : Math.min(lines.length, from + 499);
        var result = new StringBuilder();
        boolean truncated = false;
        for (int index = from - 1; index < lines.length && index < to; index++) {
            String line = lines[index];
            if (result.length() + line.length() + 1 > maxChars) {
                truncated = true;
                break;
            }
            result.append(line).append('\n');
        }
        if (to < lines.length || truncated) {
            result.append("\n[结果已截断；请缩小行范围后继续读取]\n");
        }
        return result.toString();
    }
}
