package com.zhan.jarvis.vision;

import com.zhan.jarvis.config.JarvisConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * 图片理解客户端。
 * 使用 OpenAI 兼容的 Chat Completions 多模态格式，把本地图片转成 data URI 后发送给视觉模型。
 */
public class VisionClient {

    private static final Logger log = LoggerFactory.getLogger(VisionClient.class);

    private final JarvisConfig.VisionConfig config;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public VisionClient(JarvisConfig.VisionConfig config, WebClient.Builder builder,
                        ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        var strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(12 * 1024 * 1024))
                .build();
        this.webClient = builder
                .exchangeStrategies(strategies)
                .baseUrl(stripTrailingSlash(config.apiBase()))
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Authorization", "Bearer " + config.apiKey())
                .build();
        log.info("VisionClient 初始化: enabled={}, apiBase={}, model={}",
                config.enabled(), config.apiBase(), config.model());
    }

    public boolean enabled() {
        return config != null
                && config.enabled()
                && hasText(config.apiKey())
                && hasText(config.apiBase())
                && hasText(config.model());
    }

    public String chat(String userPrompt, List<ImageInput> images) {
        if (!enabled()) {
            throw new IllegalStateException("视觉模型未启用或配置不完整");
        }
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("图片列表不能为空");
        }
        try {
            var root = objectMapper.createObjectNode();
            root.put("model", config.model());
            root.put("temperature", 0);
            root.put("max_tokens", config.maxTokens() > 0 ? config.maxTokens() : 1024);

            var messages = root.putArray("messages");
            var message = messages.addObject();
            message.put("role", "user");
            var content = message.putArray("content");
            content.addObject()
                    .put("type", "text")
                    .put("text", buildPrompt(userPrompt, images.size()));
            for (ImageInput input : images) {
                byte[] bytes = Files.readAllBytes(input.path());
                String mediaType = hasText(input.contentType()) ? input.contentType() : guessContentType(input.path());
                String dataUri = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
                var image = content.addObject();
                image.put("type", "image_url");
                image.putObject("image_url").put("url", dataUri);
            }

            String response = webClient.post()
                    .uri("/v1/chat/completions")
                    .bodyValue(root)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMinutes(2));
            return parseContent(response);
        } catch (Exception e) {
            throw new RuntimeException("视觉模型调用失败: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(String userPrompt, int imageCount) {
        return """
                你是 Jarvis 的视觉能力模块。请直接根据用户问题和随附图片回答用户。

                要求：
                1. 如果图片是截图，优先识别界面文字、错误信息、按钮、状态和关键区域。
                2. 如果用户让你分析图片内容，直接给出结论和依据。
                3. 如果用户让你根据图片继续完成任务，先说明你从图片中识别到的关键信息，再给出结果。
                4. 不要编造图片中看不到的信息；不确定时明确说明。
                5. 使用中文回答。

                图片数量：%d
                用户问题：%s
                """.formatted(imageCount, hasText(userPrompt) ? userPrompt : "请分析这些图片。");
    }

    private String parseContent(String response) throws Exception {
        if (response == null || response.isBlank()) {
            return "";
        }
        var root = objectMapper.readTree(response);
        var choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "";
        }
        var content = choices.get(0).path("message").path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            var sb = new StringBuilder();
            for (var part : content) {
                String text = part.path("text").asText("");
                if (!text.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append("\n");
                    }
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        return "";
    }

    private String guessContentType(Path path) {
        try {
            String detected = Files.probeContentType(path);
            if (hasText(detected)) {
                return detected;
            }
        } catch (Exception ignored) {
            // 使用扩展名兜底。
        }
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ImageInput(Path path, String contentType, String name) {}
}
