package com.zhan.jarvis.bus;

import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.concurrency.SystemBusyException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageBusTest {

    @Test
    void rejectsNewMessageWhenBoundedQueueIsFull() throws Exception {
        var bus = new MessageBus(1);
        var sessionKey = new SessionKey("http", "default", "session-1");
        var first = InboundMessage.of("message-1", sessionKey, "session-1", "user-1", "first", Map.of());
        var second = InboundMessage.of("message-2", sessionKey, "session-1", "user-1", "second", Map.of());

        bus.submit(first);
        var rejected = bus.submit(second);

        assertThat(bus.queuedCount()).isEqualTo(1);
        assertThatThrownBy(rejected::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(SystemBusyException.class);
        assertThat(bus.take()).isEqualTo(first);
    }

    @Test
    void cancelRemovesMessageThatHasNotBeenDispatched() {
        var bus = new MessageBus(1);
        var sessionKey = new SessionKey("http", "default", "session-1");
        var message = InboundMessage.of("message-1", sessionKey, "session-1", "user-1", "first", Map.of());

        bus.submit(message);
        boolean removed = bus.cancel(message.id());

        assertThat(removed).isTrue();
        assertThat(bus.queuedCount()).isZero();
        assertThat(bus.pendingCount()).isZero();
    }

    @Test
    void directFutureCancellationRemovesQueuedMessage() {
        var bus = new MessageBus(1);
        var sessionKey = new SessionKey("http", "default", "session-1");
        var message = InboundMessage.of("message-1", sessionKey, "session-1", "user-1", "first", Map.of());

        var future = bus.submit(message);
        future.cancel(false);

        assertThat(bus.queuedCount()).isZero();
        assertThat(bus.pendingCount()).isZero();
    }
}
