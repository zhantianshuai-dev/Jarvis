package com.zhan.jarvis.session;

import com.zhan.jarvis.memory.MemoryServiceClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatDisplayMessageFilterTest {

    private final ChatDisplayMessageFilter filter = new ChatDisplayMessageFilter();

    @Test
    void visibleOnlyKeepsDisplaySafeChatMessages() {
        assertThat(filter.visible(message("user", "你好", Map.of()))).isTrue();
        assertThat(filter.visible(message("assistant", "完成", Map.of("final", true)))).isTrue();
        assertThat(filter.visible(message("assistant", "确认卡片", Map.of(
                "final", true,
                "requires_confirmation", true,
                "event_type", "confirmation_card"
        )))).isTrue();
        assertThat(filter.visible(message("tool", "工具结果", Map.of("trace", true)))).isFalse();
        assertThat(filter.visible(message("assistant", "", Map.of(
                "trace", true,
                "trace_type", "assistant_tool_calls"
        )))).isFalse();
        assertThat(filter.visible(message("assistant", "隐藏上下文", Map.of("hidden", true)))).isFalse();
        assertThat(filter.visible(message("assistant", "内部事件", Map.of("display_event", false)))).isFalse();
    }

    private MemoryServiceClient.SessionMessage message(String role, String content, Map<String, Object> metadata) {
        return new MemoryServiceClient.SessionMessage("msg_1", role, content, metadata, "now");
    }
}
