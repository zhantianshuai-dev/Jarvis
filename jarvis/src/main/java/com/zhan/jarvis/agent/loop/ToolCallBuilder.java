package com.zhan.jarvis.agent.loop;

import cn.hutool.core.util.IdUtil;
import com.zhan.jarvis.llm.ChatStreamDelta;
import com.zhan.jarvis.llm.ToolCall;

/**
 * 聚合流式 tool_call delta。
 */
public class ToolCallBuilder {

    private String id;
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();

    public void append(ChatStreamDelta.ToolCallDelta delta) {
        if (delta.id() != null && !delta.id().isBlank()) {
            id = delta.id();
        }
        if (delta.name() != null) {
            name.append(delta.name());
        }
        if (delta.arguments() != null) {
            arguments.append(delta.arguments());
        }
    }

    public ToolCall build() {
        String safeId = id != null && !id.isBlank()
                ? id
                : "call_" + IdUtil.getSnowflake(1, 1).nextId();
        return new ToolCall(safeId, name.toString(), arguments.toString());
    }
}
