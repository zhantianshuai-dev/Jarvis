package com.zhan.jarvis.tool;

import com.zhan.jarvis.agent.RunMode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistrySubagentTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void subagentToolsExcludeRecursiveAndLargeChannelTools() {
        var local = new LocalMcpServer();
        local.registerAll(
                tool("read_file"),
                tool("write_file"),
                tool("exec"),
                tool("git"),
                tool("spawn"),
                tool("cron"),
                tool("imagegen"),
                tool("feishu_history_messages"),
                tool("memory_search"),
                tool("web_fetch")
        );
        var registry = new ToolRegistry(local, List.of());

        var tools = registry.listToolsForSubagent("查看 git 状态并读取文件", Set.of());
        var names = tools.stream().map(com.zhan.jarvis.llm.ToolDefinition::name).toList();

        assertThat(names)
                .contains("read_file", "write_file", "git", "memory_search", "web_fetch")
                .doesNotContain("spawn", "cron", "imagegen", "feishu_history_messages");
    }

    @Test
    void superAgentExposesDeferredToolsDirectly() {
        var local = new LocalMcpServer();
        local.registerAll(tool("git"), tool("cron"), tool("read_file"));
        var registry = new ToolRegistry(local, List.of());

        var names = registry.listToolsForMode(RunMode.SUPER_AGENT, "查看 git 状态", Set.of())
                .stream()
                .map(com.zhan.jarvis.llm.ToolDefinition::name)
                .toList();

        assertThat(names).contains("git", "cron", "read_file").doesNotContain("tool_search");
    }

    private McpTool tool(String name) {
        return new McpTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " description"; }
            @Override public tools.jackson.databind.JsonNode inputSchema() {
                return mapper.valueToTree(Map.of("type", "object"));
            }
            @Override public String execute(Map<String, Object> arguments, ToolContext ctx) {
                return "ok";
            }
        };
    }
}
