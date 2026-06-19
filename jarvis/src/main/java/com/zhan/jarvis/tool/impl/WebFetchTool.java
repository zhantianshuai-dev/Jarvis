package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.tool.McpTool;
import com.zhan.jarvis.tool.ToolContext;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 抓取网页并提取轻量文本内容。
 */
public class WebFetchTool implements McpTool {

    private static final int DEFAULT_MAX_CHARS = 50_000;
    private static final int MIN_MAX_CHARS = 100;
    private static final Pattern SCRIPT_STYLE = Pattern.compile(
            "(?is)<(script|style|noscript)[^>]*>.*?</\\1>");
    private static final Pattern TAG = Pattern.compile("(?is)<[^>]+>");

    private final ObjectMapper mapper;
    private final WebClient webClient;

    public WebFetchTool(ObjectMapper mapper, WebClient.Builder builder) {
        this.mapper = mapper;
        this.webClient = builder.build();
    }

    @Override public String name() { return "web_fetch"; }

    @Override public String description() {
        return "抓取 http/https URL 并返回正文文本。用于读取网页、文档页面或 JSON 接口内容。";
    }

    @Override public JsonNode inputSchema() {
        var schema = mapper.createObjectNode()
                .put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("url")
                .put("type", "string")
                .put("description", "要抓取的 http/https URL");
        props.putObject("extractMode")
                .put("type", "string")
                .put("description", "提取模式，目前支持 text 或 markdown，当前实现都会返回清理后的文本");
        props.putObject("maxChars")
                .put("type", "integer")
                .put("minimum", MIN_MAX_CHARS)
                .put("description", "最大返回字符数，默认 50000");
        schema.putArray("required").add("url");
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        String url = (String) arguments.get("url");
        if (url == null || url.isBlank()) {
            return "{\"error\":\"缺少 url 参数\"}";
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return toJson(Map.of("error", "URL 格式无效: " + e.getMessage(), "url", url));
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            return toJson(Map.of("error", "只允许 http/https URL", "url", url));
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return toJson(Map.of("error", "URL 缺少 host", "url", url));
        }

        int maxChars = intArg(arguments.get("maxChars"), DEFAULT_MAX_CHARS);
        maxChars = Math.max(maxChars, MIN_MAX_CHARS);

        try {
            var entity = webClient.get()
                    .uri(uri)
                    .header(HttpHeaders.USER_AGENT,
                            "Mozilla/5.0 (Macintosh; Intel Mac OS X) AppleWebKit/537.36")
                    .retrieve()
                    .toEntity(String.class)
                    .block(Duration.ofSeconds(30));
            if (entity == null) {
                return toJson(Map.of("error", "空响应", "url", url));
            }

            String contentType = entity.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            String body = entity.getBody() == null ? "" : entity.getBody();
            String text = looksLikeHtml(contentType, body) ? htmlToText(body) : body.strip();
            boolean truncated = text.length() > maxChars;
            if (truncated) {
                text = text.substring(0, maxChars);
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("url", url);
            result.put("status", entity.getStatusCode().value());
            result.put("content_type", contentType != null ? contentType : "");
            result.put("truncated", truncated);
            result.put("length", text.length());
            result.put("text", text);
            return toJson(result);
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage(), "url", url));
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"JSON 序列化失败\"}";
        }
    }

    private static int intArg(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean looksLikeHtml(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase().contains("text/html")) {
            return true;
        }
        String prefix = body.length() > 256 ? body.substring(0, 256) : body;
        prefix = prefix.stripLeading().toLowerCase();
        return prefix.startsWith("<!doctype") || prefix.startsWith("<html");
    }

    private static String htmlToText(String html) {
        String text = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|section|article|h[1-6]|li|tr)>", "\n");
        text = TAG.matcher(text).replaceAll(" ");
        return decodeEntities(text)
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static String decodeEntities(String text) {
        return text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }
}
