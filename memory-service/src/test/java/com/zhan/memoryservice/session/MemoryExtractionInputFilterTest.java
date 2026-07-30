package com.zhan.memoryservice.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryExtractionInputFilterTest {

    private final MemoryExtractionInputFilter filter = new MemoryExtractionInputFilter();

    @Test
    void keepsUserAndFinalAssistantMessagesOnly() {
        var result = filter.filter(List.of(
                Message.of("user", "我偏好中文回复"),
                Message.of("assistant", "", null, Map.of(
                        "trace", true,
                        "trace_type", "assistant_tool_calls"
                )),
                Message.of("tool", "git diff 很长", null, Map.of(
                        "trace", true,
                        "trace_type", "tool_result"
                )),
                Message.of("assistant", "好的，我记住了。", null, Map.of(
                        "final", true
                )),
                Message.of("assistant", "工具操作需要人工确认", null, Map.of(
                        "final", true,
                        "requires_confirmation", true
                ))
        ));

        assertThat(result.originalCount()).isEqualTo(5);
        assertThat(result.skippedCount()).isEqualTo(3);
        assertThat(result.messages()).extracting(Message::role)
                .containsExactly("user", "assistant");
        assertThat(result.messages()).extracting(MemoryExtractionInputFilterTest::textOf)
                .containsExactly("我偏好中文回复", "好的，我记住了。");
    }

    @Test
    void skipsHiddenContextAndTruncatesLargeUserContent() {
        String largeText = "a".repeat(5_000);
        var result = filter.filter(List.of(
                Message.of("user", "<system-reminder><workspace>/tmp</workspace></system-reminder>"),
                Message.of("user", largeText)
        ));

        assertThat(result.messages()).hasSize(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.truncatedCount()).isEqualTo(1);
        assertThat(textOf(result.messages().getFirst()))
                .contains("内容过长")
                .hasSizeLessThan(4_100);
    }

    private static String textOf(Message message) {
        return message.parts().stream()
                .filter(part -> "text".equals(part.type()))
                .map(Message.Part::text)
                .findFirst()
                .orElse("");
    }
}
