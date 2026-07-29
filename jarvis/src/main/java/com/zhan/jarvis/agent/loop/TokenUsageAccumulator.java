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
    private int contextTokens;

    public void add(ChatResponse.TokenUsage usage) {
        if (usage == null) {
            return;
        }
        promptTokens += Math.max(0, usage.promptTokens());
        completionTokens += Math.max(0, usage.completionTokens());
        totalTokens += Math.max(0, usage.totalTokens());
        // 当前上下文应取最后一次模型请求的输入，而非 Agent 多轮调用的累计输入。
        contextTokens = Math.max(0, usage.promptTokens());
    }

    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("prompt_tokens", promptTokens);
        map.put("completion_tokens", completionTokens);
        map.put("total_tokens", totalTokens);
        map.put("context_tokens", contextTokens);
        return map;
    }
}
