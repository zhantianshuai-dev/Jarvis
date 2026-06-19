package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.config.JarvisConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * 图片生成 HTTP 客户端 — 通过 OpenAI Responses API 调用 gpt-image-2。
 * <p>
 * 使用 /v1/responses 端点，支持多张参考图片（URL 或 base64 data URI），
 * 通过 image_generation tool 触发图片生成。
 */
public class ImageGenClient {

    private static final Logger log = LoggerFactory.getLogger(ImageGenClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final JarvisConfig.ImageGenConfig config;

    public ImageGenClient(JarvisConfig.ImageGenConfig config, WebClient.Builder builder,
                          ObjectMapper objectMapper) {
        this.config = config;
        var strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        // 不覆盖 clientConnector，沿用 common 模块 WebClientConfig 配置的 HTTP/1.1 + 代理
        this.webClient = builder
                .exchangeStrategies(strategies)
                .baseUrl(stripTrailingSlash(config.apiBase()))
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Authorization", "Bearer " + config.apiKey())
                .build();
        this.objectMapper = objectMapper;
        log.info("ImageGenClient 初始化: apiBase={}, model={}", config.apiBase(), config.model());
    }

    /**
     * 基于参考图片生成新图片。
     *
     * @param prompt              文本描述
     * @param referenceImagePaths 参考图片路径列表（URL 直接使用，本地文件编码为 base64）
     * @param workspaceDir        工作目录，生成的图片保存到 workspaceDir/generated_images/
     * @return 生成图片的绝对路径
     */
    public String generate(String prompt, List<String> referenceImagePaths, String workspaceDir) {
        // 当有参考图时，注入强指令让模型以参考图为视觉唯一来源
        if (referenceImagePaths != null && !referenceImagePaths.isEmpty()) {
            prompt = "CRITICAL: Use the attached reference image(s) as the ONLY source for the pet's visual identity. "
                   + "Match fur color, ear shape, eye shape, body proportions, and facial structure exactly from the reference. "
                   + "Ignore any text description that conflicts with the reference. "
                   + "The reference images show the ACTUAL pet to reproduce.\n\n" + prompt;
        }

        // 1. 构建 input content 数组
        var contentArray = objectMapper.createArrayNode();

        // 文本 prompt
        contentArray.addObject()
                .put("type", "input_text")
                .put("text", prompt);

        // 参考图片（支持多张）
        if (referenceImagePaths != null) {
            for (String path : referenceImagePaths) {
                String imageUrl;
                if (path.startsWith("http://") || path.startsWith("https://")) {
                    imageUrl = path;
                } else {
                    try {
                        byte[] raw = Files.readAllBytes(Path.of(path));
                        byte[] bytes = compressIfNeeded(raw, path);
                        imageUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes);
                        log.info("参考图片已编码: {} ({} bytes → {} bytes)", path, raw.length, bytes.length);
                    } catch (IOException e) {
                        log.warn("读取参考图片失败: {} — {}", path, e.toString());
                        continue;
                    }
                }
                var imageNode = contentArray.addObject();
                imageNode.put("type", "input_image");
                imageNode.putObject("image_url").put("url", imageUrl);
            }
        }

        // 构建 user message
        var userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.set("content", contentArray);

        var inputArray = objectMapper.createArrayNode();
        inputArray.add(userMessage);

        // tools
        var toolsArray = objectMapper.createArrayNode();
        toolsArray.addObject().put("type", "image_generation");

        // 请求体
        var requestBody = objectMapper.createObjectNode();
        requestBody.put("model", config.model());
        requestBody.set("input", inputArray);
        requestBody.set("tools", toolsArray);

        int refCount = referenceImagePaths != null ? referenceImagePaths.size() : 0;
        String bodyStr = requestBody.toString();
        log.info("ImageGenClient 请求: prompt={}, referenceImages={}, bodySize={} bytes", prompt, refCount, bodyStr.length());

        // 2. 调用 API（连接中断自动重试最多 3 次）
        String responseJson = null;
        Exception lastException = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                responseJson = webClient.post()
                        .uri("/v1/responses")
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofMinutes(3));
                break; // 成功，跳出重试循环
            } catch (Exception e) {
                lastException = e;
                if (attempt < 3 && isConnectionError(e)) {
                    log.warn("API 连接中断，第{}次重试...", attempt + 1);
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                } else {
                    throw new RuntimeException("图片生成 API 调用失败: " + e.getMessage(), e);
                }
            }
        }
        if (responseJson == null) {
            throw new RuntimeException("图片生成 API 调用失败（重试3次后仍失败）: " + lastException.getMessage(), lastException);
        }

        // 3. 解析响应：从 output 中找 image_generation_call
        try {
            var root = objectMapper.readTree(responseJson);
            var outputArray = root.get("output");
            if (outputArray == null || !outputArray.isArray()) {
                throw new RuntimeException("API 响应中无 output: " + responseJson);
            }

            String imageBase64 = null;
            for (var item : outputArray) {
                if ("image_generation_call".equals(item.path("type").asText())) {
                    imageBase64 = item.path("result").asText();
                    break;
                }
            }

            if (imageBase64 == null || imageBase64.isBlank()) {
                throw new RuntimeException("API 响应中无 image_generation_call: " + responseJson);
            }

            byte[] imageBytes = Base64.getDecoder().decode(imageBase64);

            Path outputDir = Path.of(workspaceDir, "generated_images");
            Files.createDirectories(outputDir);

            String fileName = UUID.randomUUID() + ".png";
            Path outputFile = outputDir.resolve(fileName);
            Files.write(outputFile, imageBytes);

            log.info("图片已保存: {} ({} bytes)", outputFile, imageBytes.length);
            return outputFile.toAbsolutePath().toString();

        } catch (IOException e) {
            throw new RuntimeException("保存生成图片失败: " + e.getMessage(), e);
        }
    }

    private static final int MAX_IMAGE_DIMENSION = 1024;

    /** 若图片最长边超过 1024px 则缩放压缩，减少请求体体积 */
    private static byte[] compressIfNeeded(byte[] original, String path) {
        try {
            var image = ImageIO.read(new ByteArrayInputStream(original));
            if (image == null) return original;

            int w = image.getWidth();
            int h = image.getHeight();
            int maxDim = Math.max(w, h);
            if (maxDim <= MAX_IMAGE_DIMENSION) return original;

            double ratio = (double) MAX_IMAGE_DIMENSION / maxDim;
            int nw = (int) (w * ratio);
            int nh = (int) (h * ratio);

            var resized = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            var g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, nw, nh, null);
            g.dispose();

            var out = new ByteArrayOutputStream();
            ImageIO.write(resized, "jpg", out);
            log.debug("图片压缩: {} ({}x{} → {}x{})", path, w, h, nw, nh);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("图片压缩失败，使用原始大小: {}", e.toString());
            return original;
        }
    }

    private String detectMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/png";
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 判断是否为连接中断类错误，这类错误重试有意义 */
    private static boolean isConnectionError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        return msg.contains("header parser received no bytes")
                || msg.contains("EOF")
                || msg.contains("Connection reset")
                || msg.contains("timeout");
    }
}