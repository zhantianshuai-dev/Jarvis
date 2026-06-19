package com.zhan.memoryservice.llm;

import tools.jackson.databind.ObjectMapper;
import com.zhan.memoryservice.config.MemoryServiceConfig;
import com.zhan.memoryservice.exception.MemoryServiceException.EmbeddingException;
import com.zhan.memoryservice.model.TokenUsage;
import com.zhan.memoryservice.tracker.TokenUsageTracker;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * OpenAI 兼容的 Embedding provider，支持百炼 text-embedding-v4 等。
 * <p>
 * 通过 WebClient 调 /embeddings，apiBase 中已包含版本路径前缀。
 * 代理配置由 WebClientConfig 统一管理，Token 用量自动记录到 H2。
 */
@Component
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final MemoryServiceConfig.EmbeddingConfig config;
    private final TokenUsageTracker tracker;

    public OpenAiEmbeddingProvider(MemoryServiceConfig memoryServiceConfig, ObjectMapper objectMapper,
                                   WebClient.Builder builder, TokenUsageTracker tracker) {
        this.config = memoryServiceConfig.embedding();
        this.objectMapper = objectMapper;
        this.tracker = tracker;
        this.webClient = builder
                .baseUrl(stripTrailingSlash(config.apiBase()))
                .defaultHeader("Authorization", "Bearer " + config.apiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 用于获取embed后的结果，返回float数组
     */
    @Override
    public float[] embed(String text) {
        var body = Map.of(
                "model", config.model(),
                "input", text);

        String raw = webClient.post()
                .uri("/embeddings")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        try {
            var root = objectMapper.readTree(raw);
            parseUsageAndTrack(root);
            var arr = root.at("/data/0/embedding");
            float[] result = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                result[i] = arr.get(i).floatValue();
            }
            return result;
        } catch (Exception e) {
            throw new EmbeddingException("解析embedding响应失败 " + raw, e);
        }
    }

    private void parseUsageAndTrack(tools.jackson.databind.JsonNode root) {
        var usage = root.get("usage");
        if (usage == null) return;
        int prompt = usage.get("prompt_tokens").asInt(0);
        int total = usage.get("total_tokens").asInt(0);
        tracker.record(TokenUsage.embedding(config.model(), prompt, total));
    }

    @Override
    public int dimension() {
        return config.dimension();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
