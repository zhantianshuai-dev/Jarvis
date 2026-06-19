package com.zhan.memoryservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * memory-service runtime configuration.
 */
@ConfigurationProperties(prefix = "memory-service")
public record MemoryServiceConfig(
    LLMConfig llm,
    EmbeddingConfig embedding,
    RerankConfig rerank,
    RetrievalConfig retrieval,
    SessionWorkspaceConfig session
) {

    /**
     * Session 存储配置。
     */
    public record SessionWorkspaceConfig(
        String workspace
    ) {}

    /**
     * LLM 模型配置。
     * Used for summary generation, intent analysis, and memory extraction.
     */
    public record LLMConfig(
        String provider,
        String apiKey,
        String apiBase,
        String model,
        double temperature
    ) {}

    /**
     * Embedding 向量化模型配置。
     * Dense embedding model configuration.
     */
    public record EmbeddingConfig(
        String provider,
        String apiKey,
        String apiBase,
        String model,
        int dimension
    ) {}

    /**
     * Rerank 重排序配置（可选）。
     * Supports Cohere /v1/rerank or compatible rerank APIs.
     * provider 为空时不启用 rerank，直接使用向量分数。
     */
    public record RerankConfig(
        String provider,         // cohere | openai-compatible | "" (不启用)
        String apiKey,
        String apiBase,
        String model,
        double threshold         // 低于此分数过滤
    ) {
        /** 是否有效配置（provider 非空即为启用） */
        public boolean isAvailable() {
            return provider != null && !provider.isBlank();
        }
    }

    /**
     * 检索参数配置。
     * Controls hierarchical retrieval behavior.
     */
    public record RetrievalConfig(
        int maxConvergenceRounds,
        int globalSearchTopK,
        int maxRelations,
        double directoryDominanceRatio,
        double hotnessAlpha,
        double scorePropagationAlpha,
        double defaultHalfLifeDays
    ) {}
}
