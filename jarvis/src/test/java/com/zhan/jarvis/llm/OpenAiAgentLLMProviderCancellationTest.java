package com.zhan.jarvis.llm;

import com.zhan.jarvis.agent.control.TurnCancellationSource;
import com.zhan.jarvis.agent.control.TurnInterruptedException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiAgentLLMProviderCancellationTest {

    @Test
    void completedRequestDoesNotConsumeSharedRunCancellationSignal() {
        var source = new TurnCancellationSource();

        assertThat(OpenAiAgentLLMProvider.cancelOnSignal(Flux.just("first"), source.token()).blockLast())
                .isEqualTo("first");
        assertThat(source.token().cancelled().toCompletableFuture()).isNotCancelled();
        assertThat(OpenAiAgentLLMProvider.cancelOnSignal(Flux.just("second"), source.token()).blockLast())
                .isEqualTo("second");

        source.cancel("user_interrupted");

        assertThatThrownBy(() -> OpenAiAgentLLMProvider
                .cancelOnSignal(Flux.<String>never(), source.token())
                .blockLast())
                .isInstanceOf(TurnInterruptedException.class)
                .hasMessage("user_interrupted");
    }
}
