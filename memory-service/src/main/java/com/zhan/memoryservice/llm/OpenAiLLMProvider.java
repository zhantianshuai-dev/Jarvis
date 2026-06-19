package com.zhan.memoryservice.llm;

import tools.jackson.databind.ObjectMapper;
import com.zhan.memoryservice.config.MemoryServiceConfig;
import com.zhan.memoryservice.exception.MemoryServiceException.LLMException;
import com.zhan.memoryservice.model.TokenUsage;
import com.zhan.memoryservice.tracker.TokenUsageTracker;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的 LLM provider，支持 DeepSeek、百炼等。
 * <p>
 * 通过 WebClient 调 /v1/chat/completions，用 Jackson 解析响应。
 * 代理配置由 WebClientConfig 统一管理，Token 用量自动记录到 H2。
 */
@Component
public class OpenAiLLMProvider implements LLMProvider {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final MemoryServiceConfig.LLMConfig config;
    private final TokenUsageTracker tracker;

    public OpenAiLLMProvider(MemoryServiceConfig memoryServiceConfig, ObjectMapper objectMapper,
                             WebClient.Builder builder, TokenUsageTracker tracker) {
        this.config = memoryServiceConfig.llm();
        this.objectMapper = objectMapper;
        this.tracker = tracker;
        this.webClient = builder
                .baseUrl(stripTrailingSlash(config.apiBase()))
                .defaultHeader("Authorization", "Bearer " + config.apiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, config.temperature());
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, double temperature) {
        var messages = new ArrayList<Map<String, String>>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        var body = Map.of(
                "model", config.model(),
                "messages", (Object) messages,
                "temperature", (Object) temperature);

        String raw = webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        try {
            var root = objectMapper.readTree(raw);
            parseUsageAndTrack(root);
            return root.at("/choices/0/message/content").asText();
        } catch (Exception e) {
            throw new LLMException("解析LLM返回响应失败: " + raw, e);
        }
    }

    private void parseUsageAndTrack(tools.jackson.databind.JsonNode root) {
        var usage = root.get("usage");
        if (usage == null) return;
        int prompt = usage.get("prompt_tokens").asInt(0);
        int completion = usage.get("completion_tokens").asInt(0);
        int total = usage.get("total_tokens").asInt(0);
        tracker.record(TokenUsage.llm(config.model(), prompt, completion, total));
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
