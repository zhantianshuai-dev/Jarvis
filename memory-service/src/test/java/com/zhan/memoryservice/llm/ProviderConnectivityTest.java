package com.zhan.memoryservice.llm;

import com.zhan.memoryservice.config.MemoryServiceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * 验证 LLM 和 Embedding provider 连通性，打印原始 JSON 响应。
 * <p>
 * 运行方式: ./mvnw test -Dtest=ProviderConnectivityTest
 */
@SpringBootTest
class ProviderConnectivityTest {

    @Autowired
    private MemoryServiceConfig config;

    @Autowired
    private LLMProvider llmProvider;

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    // ==================== LLM ====================

    @Test
    void testLLMConnectivity() {
        System.out.println("\n==================== LLM 连通性测试 ====================");
        System.out.println("Provider: " + config.llm().provider());
        System.out.println("API Base: " + config.llm().apiBase());
        System.out.println("Model:    " + config.llm().model());

        // 1. 打印原始 JSON 请求和响应
        String baseUrl = stripTrailingSlash(config.llm().apiBase());
        var messages = List.of(
                Map.of("role", "user", "content", "你是什么模型")
        );
        var body = Map.of(
                "model", config.llm().model(),
                "messages", messages,
                "temperature", 0.0);

        System.out.println("\n--- 原始请求体 ---");
        System.out.println(toPrettyJson(body));

        WebClient rawClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + config.llm().apiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();

        String rawResponse = rawClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        System.out.println("\n--- 原始响应 ---");
        System.out.println(rawResponse);
    }

    // ==================== Embedding ====================

    @Test
    void testEmbeddingConnectivity() {
        System.out.println("\n==================== Embedding 连通性测试 ====================");
        System.out.println("Provider:    " + config.embedding().provider());
        System.out.println("API Base:    " + config.embedding().apiBase());
        System.out.println("Model:       " + config.embedding().model());
        System.out.println("Dimension:   " + config.embedding().dimension());

        String baseUrl = stripTrailingSlash(config.embedding().apiBase());
        var body = Map.of(
                "model", config.embedding().model(),
                "input", "我爱你");

        System.out.println("\n--- 原始请求体 ---");
        System.out.println(toPrettyJson(body));

        WebClient rawClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + config.embedding().apiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();

        String rawResponse = rawClient.post()
                .uri("/embeddings")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        System.out.println("\n--- 原始响应 ---");
        System.out.println(rawResponse);
    }

    // ==================== helper ====================

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** 简陋但够用的 JSON 缩进打印（不依赖额外库） */
    private static String toPrettyJson(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            var sb = new StringBuilder("{\n");
            for (var entry : m.entrySet()) {
                sb.append("  \"").append(entry.getKey()).append("\": ");
                appendValue(sb, entry.getValue(), "  ");
                sb.append(",\n");
            }
            if (!m.isEmpty()) sb.setLength(sb.length() - 2); // 去掉最后一个逗号
            sb.append("\n}");
            return sb.toString();
        }
        return String.valueOf(obj);
    }

    @SuppressWarnings("unchecked")
    private static void appendValue(StringBuilder sb, Object val, String indent) {
        if (val instanceof String s) {
            sb.append('"').append(s).append('"');
        } else if (val instanceof Number || val instanceof Boolean) {
            sb.append(val);
        } else if (val instanceof List<?> list) {
            sb.append("[\n");
            for (var item : list) {
                sb.append(indent).append("    ");
                appendValue(sb, item, indent + "    ");
                sb.append(",\n");
            }
            if (!list.isEmpty()) sb.setLength(sb.length() - 2);
            sb.append("\n").append(indent).append("]");
        } else if (val instanceof Map<?, ?> m) {
            sb.append("{\n");
            for (var entry : m.entrySet()) {
                sb.append(indent).append("  \"").append(entry.getKey()).append("\": ");
                appendValue(sb, entry.getValue(), indent + "  ");
                sb.append(",\n");
            }
            if (!m.isEmpty()) sb.setLength(sb.length() - 2);
            sb.append("\n").append(indent).append("}");
        }
    }
}
