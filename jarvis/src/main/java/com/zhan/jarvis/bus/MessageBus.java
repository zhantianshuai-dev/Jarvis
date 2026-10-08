package com.zhan.jarvis.bus;

import com.zhan.jarvis.concurrency.SystemBusyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Agent 消息总线。
 * 输入入口只负责提交消息；后台 worker 负责消费并调用 AgentLoop。
 */
public class MessageBus {

    private static final Logger log = LoggerFactory.getLogger(MessageBus.class);

    private final LinkedBlockingQueue<InboundMessage> inbound;
    private final Map<String, CompletableFuture<OutboundMessage>> pending = new ConcurrentHashMap<>();

    public MessageBus() {
        this(256);
    }

    public MessageBus(int capacity) {
        this.inbound = new LinkedBlockingQueue<>(Math.max(1, capacity));
    }

    /** 提交消息并返回可等待的结果 future。 */
    public CompletableFuture<OutboundMessage> submit(InboundMessage message) {
        var future = new CompletableFuture<OutboundMessage>();
        pending.put(message.id(), future);
        future.whenComplete((result, error) -> {
            if (future.isCancelled()) {
                pending.remove(message.id(), future);
                inbound.remove(message);
            }
        });
        if (!inbound.offer(message)) {
            pending.remove(message.id(), future);
            future.completeExceptionally(new SystemBusyException("消息队列已满，请稍后重试"));
            log.warn("InboundMessage 被拒绝，消息队列已满: id={}, sessionId={}, queueSize={}",
                    message.id(), message.sessionId(), inbound.size());
            return future;
        }
        log.debug("InboundMessage 已提交: id={}, sessionId={}", message.id(), message.sessionId());
        return future;
    }

    /** worker 阻塞获取下一条消息。 */
    public InboundMessage take() throws InterruptedException {
        return inbound.take();
    }

    /** 完成一条消息。 */
    public void complete(OutboundMessage message) {
        var future = pending.remove(message.inboundId());
        if (future != null) {
            future.complete(message);
        }
    }

    /** 标记一条消息失败。 */
    public void fail(String inboundId, Throwable error) {
        var future = pending.remove(inboundId);
        if (future != null) {
            future.completeExceptionally(error);
        }
    }

    /**
     * 取消消息结果；返回 true 表示消息尚在队列中并已被移除。
     * 已被 worker 取走时由 ActiveRunRegistry 的取消信号负责终止实际执行。
     */
    public boolean cancel(String inboundId) {
        boolean removedFromQueue = inbound.removeIf(message -> message.id().equals(inboundId));
        var future = pending.remove(inboundId);
        if (future != null) {
            future.cancel(false);
        }
        return removedFromQueue;
    }

    /** 等待消息结果。 */
    public OutboundMessage await(CompletableFuture<OutboundMessage> future, Duration timeout) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("等待 Agent 回复失败", e);
        }
    }

    public int queuedCount() {
        return inbound.size();
    }

    public int pendingCount() {
        return pending.size();
    }

    /** 判断消息是否仍等待处理；HTTP 超时或取消后会返回 false。 */
    public boolean isPending(String inboundId) {
        var future = pending.get(inboundId);
        return future != null && !future.isDone();
    }
}
