package com.zhan.jarvis.channel;

import com.zhan.jarvis.bus.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Channel 生命周期和 outbound 路由管理器。
 */
public class ChannelManager {

    private static final Logger log = LoggerFactory.getLogger(ChannelManager.class);

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    public void register(Channel channel) {
        channels.put(channel.type(), channel);
        log.info("Channel 已注册: type={}", channel.type());
    }

    public void startAll() {
        channels.values().forEach(Channel::start);
        log.info("ChannelManager 已启动 {} 个 Channel", channels.size());
    }

    public void stopAll() {
        channels.values().forEach(Channel::stop);
    }

    public void send(String channelType, OutboundMessage message) {
        var channel = channels.get(channelType);
        if (channel == null) {
            throw new IllegalArgumentException("未注册的 Channel: " + channelType);
        }
        channel.send(message);
    }

    public Collection<Channel> channels() {
        return java.util.List.copyOf(channels.values());
    }
}
