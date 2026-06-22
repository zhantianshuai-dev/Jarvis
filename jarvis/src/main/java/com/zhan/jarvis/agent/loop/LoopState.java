package com.zhan.jarvis.agent.loop;

import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.llm.Message;

import java.util.List;
import java.util.Map;

/**
 * AgentLoop 单次运行状态。
 */
public record LoopState(
        SessionKey sessionKey,
        String sessionId,
        String userId,
        Map<String, Object> metadata,
        List<Message> messages,
        int startIteration,
        boolean stream,
        Map<String, Object> outputMetadata
) {
}
