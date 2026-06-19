package com.zhan.jarvis.channel;

import com.zhan.jarvis.bus.InboundMessage;
import com.zhan.jarvis.bus.MessageBus;
import com.zhan.jarvis.bus.OutboundMessage;
import com.zhan.jarvis.server.sse.SseEventHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

/**
 * HTTP Channel 适配器。
 * ChatRouter 负责 HTTP 协议解析；该类负责把 HTTP 消息转换为 MessageBus 消息。
 */
public class HttpChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(HttpChannel.class);
    private static final String CHANNEL_ID = "default";

    private final MessageBus messageBus;
    private final SseEventHub sseEventHub;

    public HttpChannel(MessageBus messageBus, SseEventHub sseEventHub) {
        this.messageBus = messageBus;
        this.sseEventHub = sseEventHub;
    }

    @Override
    public String type() {
        return "http";
    }

    @Override
    public void start() {
        log.info("HttpChannel 已启动");
    }

    public OutboundMessage submitAndAwait(String messageId, String sessionId, String userId,
                                          String content, Duration timeout) {
        //新建一个sessionKey，用于标识哪个通道，哪个会话
        var sessionKey = new SessionKey(type(), CHANNEL_ID, sessionId);
        //封装为InboundMessage消息
        var inbound = InboundMessage.of(messageId, sessionKey, sessionId, userId, content);
        //将消息提交到消息队列中
        var future = messageBus.submit(inbound);
        try {
            return messageBus.await(future, timeout);
        } catch (RuntimeException e) {
            messageBus.cancel(messageId);
            throw e;
        }
    }

    @Override
    public void send(OutboundMessage message) {
        log.info("HttpChannel outbound: sessionId={}, inboundId={}, contentLength={}",
                message.sessionId(), message.inboundId(),
                message.content() != null ? message.content().length() : 0);
        sseEventHub.publish(message.sessionId(), "message", message.content(), "http", Map.of(
                "inbound_id", message.inboundId(),
                "user_id", message.userId() != null ? message.userId() : "",
                "channel_type", message.sessionKey().channelType(),
                "channel_id", message.sessionKey().channelId()
        ));
    }
}
