package com.zhan.jarvis.channel;

/**
 * 跨 Channel 的会话标识。
 *
 * @param channelType 通道类型，如 http / cron / heartbeat
 * @param channelId 通道实例 ID
 * @param chatId 通道内的聊天或会话 ID
 */
public record SessionKey(
        String channelType,
        String channelId,
        String chatId
) {
    public String canonical() {
        return channelType + ":" + channelId + ":" + chatId;
    }
}
