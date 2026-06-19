package com.zhan.memoryservice.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * 百炼 Rerank 实现，兼容 DashScope compatible-api 的 /rerank 接口。
 * 响应格式与 Cohere /v2/rerank 一致（results 数组含 index + relevance_score）。
 */
public class BailianRerankProvider implements RerankProvider {

    private static final Logger log = LoggerFactory.getLogger(BailianRerankProvider.class);

    private final WebClient webClient;
    private final String apiBase;
    private final String apiKey;
    private final String model;

    public BailianRerankProvider(WebClient.Builder wcb, String apiBase, String apiKey, String model) {
        this.webClient = wcb.build();
        this.apiBase = apiBase;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public List<Float> rerank(String query, List<String> documents) {
        if (documents == null || documents.isEmpty()) return List.of();

        var body = Map.of(
                "model", model,
                "query", query,
                "documents", documents
        );

        try {
            @SuppressWarnings("unchecked")
            var resp = webClient.post()
                    .uri(apiBase + "/rerank")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (resp == null) {
                log.warn("Rerank 返回 null，回退到向量分数");
                return List.of();
            }

            @SuppressWarnings("unchecked")
            var results = (List<Map<String, Object>>) resp.get("results");
            if (results == null || results.size() != documents.size()) {
                log.warn("Rerank 结果数量不匹配: expected={}, actual={}", documents.size(),
                        results != null ? results.size() : 0);
                return List.of();
            }

            float[] scoresArr = new float[documents.size()];
            for (var r : results) {
                int idx = ((Number) r.get("index")).intValue();
                double score = ((Number) r.get("relevance_score")).doubleValue();
                if (idx >= 0 && idx < scoresArr.length) {
                    scoresArr[idx] = (float) score;
                }
            }
            var scores = new java.util.ArrayList<Float>(scoresArr.length);
            for (float s : scoresArr) scores.add(s);
            return scores;
        } catch (Exception e) {
            log.warn("Rerank 调用失败，回退到向量分数: {}", e.toString());
            return List.of();
        }
    }
}
