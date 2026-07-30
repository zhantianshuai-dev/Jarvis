package com.zhan.jarvis.channel;

import com.zhan.jarvis.bus.OutboundMessage;

/**
 * 消息入口/出口通道。
 */
public interface Channel {

    /** 通道类型，如 http / cron / heartbeat。 */
    String type();

    /** 启动通道。 */
    default void start() {
        // 可选实现。
    }

    /** 停止通道。 */
    default void stop() {
        // 可选实现。
    }

    /** 发送 Agent 输出消息。 */
    void send(OutboundMessage message);
}
