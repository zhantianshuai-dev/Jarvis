package com.zhan.jarvis.bus;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按键串行、跨键并行的虚拟线程分发器。
 */
final class SessionSerialDispatcher {

    private final ConcurrentHashMap<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> pendingBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> pendingByUser = new ConcurrentHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final int maxPending;
    private final int maxPendingPerUser;
    private final int maxPendingPerSession;

    SessionSerialDispatcher() {
        this(128, 16, 8);
    }

    SessionSerialDispatcher(int maxPending, int maxPendingPerUser, int maxPendingPerSession) {
        this.maxPending = Math.max(1, maxPending);
        this.maxPendingPerUser = Math.max(1, maxPendingPerUser);
        this.maxPendingPerSession = Math.max(1, maxPendingPerSession);
    }

    boolean dispatch(String key, String userId, String threadName, Runnable task) {
        String normalizedKey = key == null || key.isBlank() ? "unknown-session" : key;
        String normalizedUserId = userId == null || userId.isBlank() ? "anonymous" : userId;
        if (!reserve(normalizedKey, normalizedUserId)) {
            return false;
        }
        CompletableFuture<Void> scheduled;
        try {
            scheduled = tails.compute(normalizedKey, (ignored, previous) -> {
                if (previous == null) {
                    return start(threadName, task);
                }
                return previous.handle((result, error) -> null)
                        .thenCompose(ignoredResult -> start(threadName, task));
            });
        } catch (RuntimeException e) {
            release(normalizedKey, normalizedUserId);
            throw e;
        }
        scheduled.whenComplete((result, error) -> {
            tails.remove(normalizedKey, scheduled);
            release(normalizedKey, normalizedUserId);
        });
        return true;
    }

    int activeSessionCount() {
        return tails.size();
    }

    int pendingCount() {
        return pending.get();
    }

    private boolean reserve(String sessionId, String userId) {
        if (pending.incrementAndGet() > maxPending) {
            pending.decrementAndGet();
            return false;
        }
        int userPending = pendingByUser.computeIfAbsent(userId, ignored -> new AtomicInteger()).incrementAndGet();
        if (userPending > maxPendingPerUser) {
            decrement(pendingByUser, userId);
            pending.decrementAndGet();
            return false;
        }
        int sessionPending = pendingBySession.computeIfAbsent(sessionId, ignored -> new AtomicInteger()).incrementAndGet();
        if (sessionPending > maxPendingPerSession) {
            decrement(pendingBySession, sessionId);
            decrement(pendingByUser, userId);
            pending.decrementAndGet();
            return false;
        }
        return true;
    }

    private void release(String sessionId, String userId) {
        decrement(pendingBySession, sessionId);
        decrement(pendingByUser, userId);
        pending.decrementAndGet();
    }

    private static void decrement(ConcurrentHashMap<String, AtomicInteger> counters, String key) {
        counters.computeIfPresent(key, (ignored, counter) -> counter.decrementAndGet() == 0 ? null : counter);
    }

    private CompletableFuture<Void> start(String threadName, Runnable task) {
        var completion = new CompletableFuture<Void>();
        Thread.ofVirtual()
                .name(threadName)
                .start(() -> {
                    try {
                        task.run();
                        completion.complete(null);
                    } catch (Throwable e) {
                        completion.completeExceptionally(e);
                    }
                });
        return completion;
    }
}
