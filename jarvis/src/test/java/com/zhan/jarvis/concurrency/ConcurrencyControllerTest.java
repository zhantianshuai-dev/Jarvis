package com.zhan.jarvis.concurrency;

import com.zhan.jarvis.config.JarvisConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConcurrencyControllerTest {

    @Test
    void serializesAgentRunsWithinSameSession() throws Exception {
        var controller = new ConcurrencyController(config(4, 4, 1_000));
        var first = controller.acquireAgent("user-1", "session-1");
        var attempting = new CountDownLatch(1);
        var acquired = new CountDownLatch(1);

        var second = CompletableFuture.runAsync(() -> {
            attempting.countDown();
            try (var ignored = controller.acquireAgent("user-1", "session-1")) {
                acquired.countDown();
            }
        });

        assertThat(attempting.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(acquired.await(100, TimeUnit.MILLISECONDS)).isFalse();
        first.close();
        assertThat(acquired.await(1, TimeUnit.SECONDS)).isTrue();
        second.get(1, TimeUnit.SECONDS);
        assertThat(controller.snapshot()).containsEntry("active_agents", 0);
    }

    @Test
    void rejectsLlmCallAfterAcquireTimeout() {
        var controller = new ConcurrencyController(config(4, 4, 50));
        try (var ignored = controller.acquireLlm()) {
            assertThatThrownBy(controller::acquireLlm)
                    .isInstanceOf(SystemBusyException.class)
                    .hasMessageContaining("LLM");
        }
    }

    @Test
    void limitsConcurrentAgentsPerUserAcrossSessions() {
        var controller = new ConcurrencyController(config(4, 1, 50));
        try (var ignored = controller.acquireAgent("user-1", "session-1")) {
            assertThatThrownBy(() -> controller.acquireAgent("user-1", "session-2"))
                    .isInstanceOf(SystemBusyException.class)
                    .hasMessageContaining("用户并发任务数");
        }
    }

    @Test
    void dropsAsyncHookWhenObservationCapacityIsFull() {
        var controller = new ConcurrencyController(config(4, 4, 50));
        var first = controller.tryAcquireAsyncHook();

        assertThat(first).isNotNull();
        assertThat(controller.tryAcquireAsyncHook()).isNull();

        first.close();
        try (var acquiredAgain = controller.tryAcquireAsyncHook()) {
            assertThat(acquiredAgain).isNotNull();
        }
    }

    private static JarvisConfig.ConcurrencyConfig config(int agents, int perUser, long timeoutMs) {
        return new JarvisConfig.ConcurrencyConfig(
                true,
                agents,
                perUser,
                16,
                8,
                4,
                16,
                1,
                4,
                4,
                2,
                4,
                2,
                1,
                timeoutMs
        );
    }
}
