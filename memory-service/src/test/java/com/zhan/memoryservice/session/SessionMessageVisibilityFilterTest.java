package com.zhan.memoryservice.session;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionMessageVisibilityFilterTest {

    private final SessionMessageVisibilityFilter filter = new SessionMessageVisibilityFilter();

    @Test
    void displayKeepsUserAndFinalAssistantOnly() {
        assertThat(filter.visibleForDisplay(Message.of("user", "你好"))).isTrue();
        assertThat(filter.visibleForDisplay(Message.of("assistant", "完成", null, Map.of("final", true)))).isTrue();
        assertThat(filter.visibleForDisplay(Message.of("assistant", "", null, Map.of(
                "trace", true,
                "trace_type", "assistant_tool_calls"
        )))).isFalse();
        assertThat(filter.visibleForDisplay(Message.of("tool", "工具结果", null, Map.of("trace", true)))).isFalse();
        assertThat(filter.visibleForDisplay(Message.of("assistant", "隐藏提示", null, Map.of("hidden", true)))).isFalse();
        assertThat(filter.visibleForDisplay(Message.of("assistant", "内部事件", null, Map.of("display_event", false)))).isFalse();
    }

    @Test
    void runtimeContextSkipsSubagentStatusEvents() {
        var subagentStatus = Message.of("assistant", "子 Agent 执行成功", null, Map.of(
                "final", true,
                "subagent_status", true
        ));

        assertThat(filter.visibleForDisplay(subagentStatus)).isTrue();
        assertThat(filter.visibleForRuntimeContext(subagentStatus)).isFalse();
    }
}
