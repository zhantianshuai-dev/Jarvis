package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.sandbox.SandboxManager;
import com.zhan.jarvis.tool.McpTool;
import com.zhan.jarvis.tool.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 执行 Shell 命令。
 */
public class ExecTool implements McpTool {

    private final ObjectMapper mapper;
    private final SandboxManager sandboxManager;

    public ExecTool(ObjectMapper mapper, SandboxManager sandboxManager) {
        this.mapper = mapper;
        this.sandboxManager = sandboxManager;
    }

    @Override public String name() { return "exec"; }

    @Override public String description() {
        return "执行 Shell 命令并返回 stdout + stderr 输出。" +
               "用于运行脚本、编译代码、安装依赖等。命令在 Agent 工作目录下执行。";
    }

    @Override public JsonNode inputSchema() {
        var schema = mapper.createObjectNode()
                .put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("command")
                .put("type", "string")
                .put("description", "要执行的 Shell 命令");
        schema.putArray("required").add("command");
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        String command = (String) arguments.get("command");
        if (command == null || command.isBlank()) {
            return "错误: 缺少 command 参数";
        }

        try {
            return sandboxManager.execute(command, ctx.effectiveWorkspaceDir()).format();
        } catch (Exception e) {
            return "命令执行异常: " + e.getMessage();
        }
    }
}
