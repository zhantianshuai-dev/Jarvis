package com.zhan.jarvis.agent.context;

import com.zhan.jarvis.tool.ToolRegistry;

import java.util.Set;

public class ToolExposureStage {

    private final ToolRegistry toolRegistry;

    public ToolExposureStage(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public String buildToolSummary(ContextBuildRequest request) {
        var tools = toolRegistry.listToolsForMode(request.runMode(), request.currentMessage(), Set.of());
        if (tools.isEmpty()) {
            return "";
        }

        var sb = new StringBuilder("你拥有以下能力：\n");
        for (var td : tools) {
            sb.append("- ").append(td.name()).append(": ").append(td.description()).append("\n");
        }
        return sb.toString();
    }

    public String buildDeferredToolsSection(ContextBuildRequest request) {
        var tools = toolRegistry.availableDeferredToolsForMode(request.runMode(), request.currentMessage());
        if (tools.isEmpty()) {
            return "";
        }

        var sb = new StringBuilder();
        sb.append("<available-deferred-tools>\n");
        sb.append("Use tool_search to load full schema before calling these tools. ");
        sb.append("Prefer query=\"select:<tool_name>\" and set group to one of: git, cron, feishu, image, subagent, mcp.\n");
        for (var tool : tools) {
            sb.append("- group=").append(tool.group())
                    .append(" name=").append(tool.name())
                    .append(" description=").append(shortText(tool.description(), 80))
                    .append("\n");
        }
        sb.append("</available-deferred-tools>");
        return sb.toString();
    }

    private String shortText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
