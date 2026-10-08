package com.zhan.jarvis.bus;

import com.zhan.jarvis.agent.AgentLoop;
import com.zhan.jarvis.channel.ChannelManager;
import com.zhan.jarvis.concurrency.SystemBusyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MessageBus 后台消费者。
 */
public class AgentMessageWorker {

    private static final Logger log = LoggerFactory.getLogger(AgentMessageWorker.class);

    private final MessageBus bus;
    private final AgentLoop agentLoop;
    private final ChannelManager channelManager;
    private final SessionSerialDispatcher sessionDispatcher;
    private volatile Thread dispatcher;
    private volatile boolean running;

    public AgentMessageWorker(MessageBus bus, AgentLoop agentLoop, ChannelManager channelManager) {
        this(bus, agentLoop, channelManager, 128, 16, 8);
    }

    public AgentMessageWorker(MessageBus bus, AgentLoop agentLoop, ChannelManager channelManager,
                              int maxPending, int maxPendingPerUser, int maxPendingPerSession) {
        this.bus = bus;
        this.agentLoop = agentLoop;
        this.channelManager = channelManager;
        this.sessionDispatcher = new SessionSerialDispatcher(maxPending, maxPendingPerUser, maxPendingPerSession);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        dispatcher = Thread.ofVirtual()
                .name("jarvis-message-dispatcher")
                .start(this::runLoop);
        log.info("AgentMessageWorker 已启动: 单分发器 + 会话级串行队列");
    }

    public synchronized void stop() {
        running = false;
        if (dispatcher != null) {
            dispatcher.interrupt();
            dispatcher = null;
        }
    }

    private void runLoop() {
        while (running) {
            try {
                // 不断尝试拉取消息总线中的消息。
                InboundMessage message = bus.take();
                log.info("分发 InboundMessage: id={}, sessionId={}", message.id(), message.sessionId());
                dispatch(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    /**
     * 不同会话立即并行；同一会话通过 CompletableFuture 尾链保持提交顺序。
     */
    private void dispatch(InboundMessage message) {
        boolean accepted = sessionDispatcher.dispatch(message.sessionId(), message.userId(),
                "jarvis-message-task-" + message.id(), () -> process(message));
        if (!accepted) {
            bus.fail(message.id(), new SystemBusyException("当前消息等待队列已满，请稍后重试"));
            log.warn("InboundMessage 被并发调度器拒绝: id={}, sessionId={}, userId={}",
                    message.id(), message.sessionId(), message.userId());
        }
    }

    private void process(InboundMessage message) {
        // HTTP 请求可能在会话队列中等待时已经超时或取消，避免继续消耗 Agent/LLM 配额。
        if (!bus.isPending(message.id())) {
            log.debug("跳过已取消的 InboundMessage: id={}, sessionId={}", message.id(), message.sessionId());
            return;
        }
        try {
            String reply = agentLoop.run(message);
            var metadata = new java.util.LinkedHashMap<String, Object>();
            metadata.put("source", "AgentMessageWorker");
            metadata.putAll(message.metadata());
            var outbound = OutboundMessage.of(
                    message.id(),
                    message.sessionKey(),
                    message.sessionId(),
                    message.userId(),
                    reply,
                    metadata
            );
            bus.complete(outbound);
            deliverIfNeeded(message, outbound);
        } catch (Exception e) {
            bus.fail(message.id(), e);
            log.warn("InboundMessage 处理失败: id={}, error={}", message.id(), e.getMessage());
            log.debug("InboundMessage 处理失败详情", e);
        }
    }

    private void deliverIfNeeded(InboundMessage inbound, OutboundMessage outbound) {
        if (!shouldDeliver(inbound)) {
            return;
        }
        try {
            channelManager.send(outbound.sessionKey().channelType(), outbound);
        } catch (Exception e) {
            log.warn("OutboundMessage 回投 Channel 失败: id={}, channel={}, error={}",
                    outbound.inboundId(), outbound.sessionKey().channelType(), e.getMessage());
            log.debug("OutboundMessage 回投异常详情", e);
        }
    }

    private boolean shouldDeliver(InboundMessage inbound) {
        Object explicit = inbound.metadata().get("auto_deliver");
        if (explicit instanceof Boolean value) {
            return value;
        }
        if (explicit instanceof String value && !value.isBlank()) {
            return Boolean.parseBoolean(value);
        }

        String channelType = inbound.sessionKey().channelType();
        return !"http".equals(channelType) && !"heartbeat".equals(channelType);
    }
}
