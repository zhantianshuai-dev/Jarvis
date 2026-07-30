package com.zhan.jarvis.agent.loop;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TokenUsageAccumulatorTest {

    @Test
    void restoresTokenUsageFromCheckpointMap() {
        var restored = TokenUsageAccumulator.fromMap(Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 20,
                "total_tokens", 120,
                "context_tokens", 80
        ));

        assertThat(restored.toMap())
                .containsEntry("prompt_tokens", 100)
                .containsEntry("completion_tokens", 20)
                .containsEntry("total_tokens", 120)
                .containsEntry("context_tokens", 80);
    }
}
