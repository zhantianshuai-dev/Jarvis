package com.zhan.memoryservice.model;

import java.time.Instant;

/**
 * 单次 LLM/Embedding 调用的 Token 消耗记录。
 */
public record TokenUsage(
    String callType,        // "llm" | "embedding"
    String model,           // 使用的模型名称
    int promptTokens,       // 输入 token 数
    int completionTokens,   // 输出 token 数（embedding 调用时为 0）
    int totalTokens,        // 总 token 数
    Instant createdAt
) {
    public static TokenUsage llm(String model, int promptTokens, int completionTokens, int totalTokens) {
        return new TokenUsage("llm", model, promptTokens, completionTokens, totalTokens, Instant.now());
    }

    public static TokenUsage embedding(String model, int promptTokens, int totalTokens) {
        return new TokenUsage("embedding", model, promptTokens, 0, totalTokens, Instant.now());
    }
}
