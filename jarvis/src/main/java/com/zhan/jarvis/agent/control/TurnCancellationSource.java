package com.zhan.jarvis.agent.control;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Run 取消信号源。父级取消会级联到通过 {@link #child()} 创建的子级。
 */
public final class TurnCancellationSource implements AutoCloseable {

    static final TurnCancellationToken NONE = new TurnCancellationToken() {
        private final CompletableFuture<Void> signal = new CompletableFuture<>();

        @Override
        public boolean isCancellationRequested() {
            return false;
        }

        @Override
        public String reason() {
            return "";
        }

        @Override
        public CompletionStage<Void> cancelled() {
            return signal;
        }

        @Override
        public AutoCloseable onCancel(Runnable callback) {
            return () -> { };
        }
    };

    private final State state = new State();
    private final AutoCloseable parentRegistration;

    public TurnCancellationSource() {
        this.parentRegistration = null;
    }

    private TurnCancellationSource(TurnCancellationToken parent) {
        this.parentRegistration = parent.onCancel(() -> cancel(parent.reason()));
    }

    public static TurnCancellationSource linkedTo(TurnCancellationToken parent) {
        return new TurnCancellationSource(parent == null ? TurnCancellationToken.none() : parent);
    }

    public TurnCancellationToken token() {
        return state;
    }

    public TurnCancellationSource child() {
        return new TurnCancellationSource(token());
    }

    public boolean cancel(String reason) {
        return state.cancel(normalizeReason(reason));
    }

    @Override
    public void close() {
        if (parentRegistration == null) {
            return;
        }
        try {
            parentRegistration.close();
        } catch (Exception ignored) {
            // 取消监听器注销失败不影响 Run 收尾。
        }
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "interrupted" : reason;
    }

    private static final class State implements TurnCancellationToken {
        private final AtomicReference<String> reason = new AtomicReference<>();
        private final AtomicLong listenerSequence = new AtomicLong();
        private final ConcurrentHashMap<Long, Runnable> listeners = new ConcurrentHashMap<>();
        private final CompletableFuture<Void> signal = new CompletableFuture<>();

        @Override
        public boolean isCancellationRequested() {
            return reason.get() != null;
        }

        @Override
        public String reason() {
            String value = reason.get();
            return value == null ? "" : value;
        }

        @Override
        public CompletionStage<Void> cancelled() {
            return signal;
        }

        @Override
        public AutoCloseable onCancel(Runnable callback) {
            if (callback == null) {
                return () -> { };
            }
            if (isCancellationRequested()) {
                runQuietly(callback);
                return () -> { };
            }
            long id = listenerSequence.incrementAndGet();
            listeners.put(id, callback);
            if (isCancellationRequested() && listeners.remove(id, callback)) {
                runQuietly(callback);
            }
            return () -> listeners.remove(id, callback);
        }

        private boolean cancel(String cancellationReason) {
            if (!reason.compareAndSet(null, cancellationReason)) {
                return false;
            }
            signal.complete(null);
            var callbacks = listeners.values().toArray(Runnable[]::new);
            listeners.clear();
            for (Runnable callback : callbacks) {
                runQuietly(callback);
            }
            return true;
        }

        private static void runQuietly(Runnable callback) {
            try {
                callback.run();
            } catch (RuntimeException ignored) {
                // 单个资源清理失败不能阻断其他取消监听器。
            }
        }
    }
}
