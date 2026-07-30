package com.zhan.jarvis.agent.loop;

import com.zhan.jarvis.llm.ChatResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 累加一次 AgentLoop 中多轮 LLM 请求的 token 使用量。
 */
public class TokenUsageAccumulator {

    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
    private int contextTokens;

    public static TokenUsageAccumulator fromMap(Map<String, Object> source) {
        var accumulator = new TokenUsageAccumulator();
        if (source == null || source.isEmpty()) {
            return accumulator;
        }
        accumulator.promptTokens = intValue(source.get("prompt_tokens"));
        accumulator.completionTokens = intValue(source.get("completion_tokens"));
        accumulator.totalTokens = intValue(source.get("total_tokens"));
        accumulator.contextTokens = intValue(source.get("context_tokens"));
        return accumulator;
    }

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

    private static int intValue(Object value) {
        if (value instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
