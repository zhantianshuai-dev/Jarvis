package com.zhan.jarvis.llm;

import java.util.Map;

/**
 * LLM provider lifecycle event emitted while streaming.
 */
public record LlmProviderEvent(
        String type,
        String provider,
        String nextProvider,
        int attempt,
        int maxAttempts,
        long waitMs,
        String reason,
        String message
) {
    public static LlmProviderEvent retry(String provider, int attempt, int maxAttempts,
                                         long waitMs, String reason) {
        return new LlmProviderEvent(
                "llm_retry",
                provider,
                "",
                attempt,
                maxAttempts,
                waitMs,
                reason,
                "LLM provider " + provider + " 暂时不可用，" + waitMs + "ms 后重试。"
        );
    }

    public static LlmProviderEvent fallback(String provider, String nextProvider, String reason) {
        return new LlmProviderEvent(
                "llm_fallback",
                provider,
                nextProvider == null ? "" : nextProvider,
                0,
                0,
                0,
                reason,
                nextProvider == null || nextProvider.isBlank()
                        ? "LLM provider " + provider + " 调用失败。"
                        : "LLM provider " + provider + " 调用失败，切换到 " + nextProvider + "。"
        );
    }

    public static LlmProviderEvent circuitOpen(String provider, String nextProvider, long waitMs) {
        return new LlmProviderEvent(
                "llm_circuit_open",
                provider,
                nextProvider == null ? "" : nextProvider,
                0,
                0,
                waitMs,
                "circuit_open",
                "LLM provider " + provider + " 连续失败，熔断中，跳过该 provider。"
        );
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "provider", provider == null ? "" : provider,
                "next_provider", nextProvider == null ? "" : nextProvider,
                "attempt", attempt,
                "max_attempts", maxAttempts,
                "wait_ms", waitMs,
                "reason", reason == null ? "" : reason,
                "message", message == null ? "" : message
        );
    }
}
