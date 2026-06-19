package com.zhan.jarvis.bus;

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

    private final LinkedBlockingQueue<InboundMessage> inbound = new LinkedBlockingQueue<>();
    private final Map<String, CompletableFuture<OutboundMessage>> pending = new ConcurrentHashMap<>();

    /** 提交消息并返回可等待的结果 future。 */
    public CompletableFuture<OutboundMessage> submit(InboundMessage message) {
        var future = new CompletableFuture<OutboundMessage>();
        pending.put(message.id(), future);
        inbound.offer(message);
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

    /** 取消等待中的消息结果；已被 worker 取走的任务仍会继续执行。 */
    public void cancel(String inboundId) {
        var future = pending.remove(inboundId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /** 等待消息结果。 */
    public OutboundMessage await(CompletableFuture<OutboundMessage> future, Duration timeout) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("等待 Agent 回复失败", e);
        }
    }
}
