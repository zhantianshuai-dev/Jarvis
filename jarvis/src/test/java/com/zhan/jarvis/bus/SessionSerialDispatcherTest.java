package com.zhan.jarvis.bus;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SessionSerialDispatcherTest {

    @Test
    void preservesOrderWithinSameSession() throws Exception {
        var dispatcher = new SessionSerialDispatcher();
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);

        assertThat(dispatcher.dispatch("session-1", "user-1", "first", () -> {
            firstStarted.countDown();
            await(releaseFirst);
        })).isTrue();
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(dispatcher.dispatch("session-1", "user-1", "second", secondStarted::countDown)).isTrue();
        assertThat(secondStarted.await(100, TimeUnit.MILLISECONDS)).isFalse();

        releaseFirst.countDown();
        assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void runsDifferentSessionsConcurrently() throws Exception {
        var dispatcher = new SessionSerialDispatcher();
        var bothStarted = new CountDownLatch(2);
        var release = new CountDownLatch(1);

        assertThat(dispatcher.dispatch("session-1", "user-1", "first", () -> {
            bothStarted.countDown();
            await(release);
        })).isTrue();
        assertThat(dispatcher.dispatch("session-2", "user-2", "second", () -> {
            bothStarted.countDown();
            await(release);
        })).isTrue();

        assertThat(bothStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(dispatcher.activeSessionCount()).isEqualTo(2);
        release.countDown();
    }

    @Test
    void rejectsWhenPerSessionQueueIsFull() throws Exception {
        var dispatcher = new SessionSerialDispatcher(4, 4, 1);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        assertThat(dispatcher.dispatch("session-1", "user-1", "first", () -> {
            started.countDown();
            await(release);
        })).isTrue();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(dispatcher.dispatch("session-1", "user-1", "second", () -> { })).isFalse();
        assertThat(dispatcher.pendingCount()).isEqualTo(1);
        release.countDown();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
