package com.zhan.jarvis.tool;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * HTTP/SSE MCP 传输。
 * <p>
 * 支持标准 MCP SSE：GET 建立 SSE 流，读取 endpoint 事件，再 POST JSON-RPC 到 message endpoint，
 * 响应从 SSE message 事件按 id 匹配。transport=http/streamable-http 时保留直接 POST 兼容路径。
 */
public class SseTransport implements McpTransport {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final McpServerConfig config;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final boolean standardSse;
    private final CountDownLatch endpointReady = new CountDownLatch(1);
    private final Map<String, CompletableFuture<ObjectNode>> pendingResponses = new ConcurrentHashMap<>();
    private volatile String messageEndpoint;
    private volatile Throwable streamError;
    private Disposable sseSubscription;

    public SseTransport(McpServerConfig config, ObjectMapper objectMapper, WebClient.Builder builder) {
        if (config.url() == null || config.url().isBlank()) {
            throw new IllegalArgumentException("HTTP/SSE MCP Server 缺少 url: " + config.name());
        }
        this.config = config;
        this.objectMapper = objectMapper;
        this.standardSse = "sse".equalsIgnoreCase(config.transport());

        var webClientBuilder = builder.clone()
                .baseUrl(stripTrailingSlash(config.url()))
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json, text/event-stream");
        config.headers().forEach(webClientBuilder::defaultHeader);
        this.webClient = webClientBuilder.build();

        if (standardSse) {
            startSseStream();
        }
    }

    @Override
    public ObjectNode send(ObjectNode request) {
        if (standardSse) {
            return sendViaSse(request);
        }
        return sendDirectPost(request);
    }

    @Override
    public void notify(ObjectNode notification) {
        if (standardSse) {
            postToMessageEndpoint(notification);
            return;
        }
        webClient.post()
                .bodyValue(notification)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
    }

    @Override
    public void close() {
        if (sseSubscription != null) {
            sseSubscription.dispose();
        }
    }

    private ObjectNode sendDirectPost(ObjectNode request) {
        try {
            String raw = webClient.post()
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(60));
            return parseResponse(raw);
        } catch (Exception e) {
            throw new RuntimeException("HTTP/SSE MCP 请求失败", e);
        }
    }

    private ObjectNode sendViaSse(ObjectNode request) {
        String id = request.path("id").asText();
        var future = new CompletableFuture<ObjectNode>();
        pendingResponses.put(id, future);
        try {
            String raw = postToMessageEndpoint(request);
            if (raw != null && !raw.isBlank()) {
                var immediate = parseResponse(raw);
                if (id.equals(immediate.path("id").asText())) {
                    pendingResponses.remove(id);
                    return immediate;
                }
            }
            return future.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            pendingResponses.remove(id);
            throw new RuntimeException("MCP SSE 请求失败: " + config.name(), e);
        }
    }

    private String postToMessageEndpoint(ObjectNode request) {
        ensureEndpointReady();
        return webClient.post()
                .uri(messageEndpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .block(DEFAULT_TIMEOUT);
    }

    private void startSseStream() {
        sseSubscription = webClient.get()
                .uri("")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE_TYPE)
                .subscribe(this::handleSseEvent, error -> {
                    streamError = error;
                    endpointReady.countDown();
                    pendingResponses.values().forEach(f -> f.completeExceptionally(error));
                });
    }

    private void handleSseEvent(ServerSentEvent<String> event) {
        String data = event.data();
        if (data == null || data.isBlank()) return;

        String eventName = event.event();
        if ("endpoint".equals(eventName) || looksLikeEndpoint(data)) {
            messageEndpoint = resolveEndpoint(data.strip());
            endpointReady.countDown();
            return;
        }

        try {
            var node = objectMapper.readTree(data);
            if (!node.isObject()) return;
            var response = (ObjectNode) node;
            String id = response.path("id").asText();
            var future = pendingResponses.remove(id);
            if (future != null) {
                future.complete(response);
            }
        } catch (Exception e) {
            streamError = e;
        }
    }

    private void ensureEndpointReady() {
        try {
            if (!endpointReady.await(30, TimeUnit.SECONDS)) {
                throw new RuntimeException("等待 MCP SSE endpoint 超时: " + config.name());
            }
            if (streamError != null) {
                throw new RuntimeException("MCP SSE 流异常: " + config.name(), streamError);
            }
            if (messageEndpoint == null || messageEndpoint.isBlank()) {
                throw new RuntimeException("MCP SSE 未收到 message endpoint: " + config.name());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待 MCP SSE endpoint 被中断: " + config.name(), e);
        }
    }

    private String resolveEndpoint(String endpoint) {
        URI endpointUri = URI.create(endpoint);
        if (endpointUri.isAbsolute()) {
            return endpoint;
        }
        return URI.create(config.url()).resolve(endpointUri).toString();
    }

    private static boolean looksLikeEndpoint(String data) {
        String value = data.strip();
        return value.startsWith("/") || value.startsWith("http://") || value.startsWith("https://");
    }

    private ObjectNode parseResponse(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("MCP Server 返回空响应");
        }
        String payload = raw.strip();
        if (!payload.startsWith("{")) {
            payload = extractSseData(payload);
        }
        var node = objectMapper.readTree(payload);
        if (!node.isObject()) {
            throw new IllegalArgumentException("MCP Server 响应不是 JSON object: " + payload);
        }
        return (ObjectNode) node;
    }

    private static String extractSseData(String raw) {
        for (String line : raw.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("data:")) {
                String data = trimmed.substring("data:".length()).strip();
                if (!data.isBlank() && !"[DONE]".equals(data)) {
                    return data;
                }
            }
        }
        throw new IllegalArgumentException("无法从 SSE 响应提取 data JSON");
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
