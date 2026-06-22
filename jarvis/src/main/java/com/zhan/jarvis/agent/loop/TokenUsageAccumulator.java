package com.zhan.jarvis.agent.loop;

import com.zhan.jarvis.llm.ChatResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 累加一次 AgentLoop 中多轮 LLM 请求的 token usage。
 */
public class TokenUsageAccumulator {

    private int promptTokens;
    private int completionTokens;
    private int totalTokens;

    public void add(ChatResponse.TokenUsage usage) {
        if (usage == null) {
            return;
        }
        promptTokens += Math.max(0, usage.promptTokens());
        completionTokens += Math.max(0, usage.completionTokens());
        totalTokens += Math.max(0, usage.totalTokens());
    }

    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("prompt_tokens", promptTokens);
        map.put("completion_tokens", completionTokens);
        map.put("total_tokens", totalTokens);
        return map;
    }
}
