package com.zhan.jarvis.bus;

import com.zhan.jarvis.agent.AgentLoop;
import com.zhan.jarvis.channel.ChannelManager;
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
    private volatile boolean running;

    public AgentMessageWorker(MessageBus bus, AgentLoop agentLoop, ChannelManager channelManager) {
        this.bus = bus;
        this.agentLoop = agentLoop;
        this.channelManager = channelManager;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        Thread.startVirtualThread(this::runLoop);
        log.info("AgentMessageWorker 已启动");
    }

    private void runLoop() {
        while (running) {
            InboundMessage message = null;
            try {
                //不断尝试去拉取messageBus中的消息
                message = bus.take();
                log.info("消费 InboundMessage: id={}, sessionId={}", message.id(), message.sessionId());
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
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            } catch (Exception e) {
                if (message != null) {
                    bus.fail(message.id(), e);
                    log.warn("InboundMessage 处理失败: id={}, error={}", message.id(), e.getMessage());
                } else {
                    log.warn("AgentMessageWorker 异常: {}", e.getMessage());
                }
                log.debug("AgentMessageWorker 异常详情", e);
            }
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
