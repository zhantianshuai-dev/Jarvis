package com.zhan.jarvis.llm;

import com.zhan.jarvis.config.JarvisConfig;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容的 Agent LLM Provider，支持 tool_calls 往返。
 * <p>
 * WebClient.Builder 由 common 模块的 WebClientConfig 提供（含 HTTP/1.1 + 代理）。
 * ObjectMapper 由 Spring Boot WebFlux 自动配置（Jackson 3.x tools.jackson）。
 */
public class OpenAiAgentLLMProvider implements AgentLLMProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAgentLLMProvider.class);
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final JarvisConfig.LLMConfig config;
    private final List<LlmEndpoint> endpoints;
    private final int maxRetries;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final boolean circuitBreakerEnabled;
    private final int circuitFailureThreshold;
    private final Duration circuitRecoveryTimeout;

    public OpenAiAgentLLMProvider(JarvisConfig.LLMConfig config, ObjectMapper objectMapper,
                                   WebClient.Builder builder) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.endpoints = buildEndpoints(config, builder);
        var retry = config.retry();
        this.maxRetries = retry != null ? Math.max(0, retry.maxRetries()) : 3;
        this.initialBackoff = Duration.ofMillis(retry != null && retry.initialBackoffMs() > 0
                ? retry.initialBackoffMs() : 1000);
        this.maxBackoff = Duration.ofMillis(retry != null && retry.maxBackoffMs() > 0
                ? retry.maxBackoffMs() : 8000);
        var circuitBreaker = config.circuitBreaker();
        this.circuitBreakerEnabled = circuitBreaker == null || circuitBreaker.enabled();
        this.circuitFailureThreshold = circuitBreaker != null && circuitBreaker.failureThreshold() > 0
                ? circuitBreaker.failureThreshold() : 3;
        this.circuitRecoveryTimeout = Duration.ofMillis(circuitBreaker != null && circuitBreaker.recoveryTimeoutMs() > 0
                ? circuitBreaker.recoveryTimeoutMs() : 60_000);
        log.info("Agent LLM endpoints: {}", endpoints.stream().map(LlmEndpoint::provider).toList());
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        var errors = new ArrayList<String>();

        for (var endpoint : endpoints) {
            if (isCircuitOpen(endpoint)) {
                String error = endpoint.provider() + "(circuit_open)";
                errors.add(error);
                log.warn("LLM provider 熔断中，跳过: provider={}, remainingMs={}",
                        endpoint.provider(), endpoint.circuit().remainingOpenMs());
                continue;
            }

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                var body = buildRequestBody(endpoint, messages, tools, false);
                log.debug("LLM 请求: provider={}, attempt={}/{}, {} 条消息, {} 个工具, 模型={}",
                        endpoint.provider(), attempt + 1, maxRetries + 1, messages.size(),
                        tools != null ? tools.size() : 0, endpoint.model());
                logRequestBody(endpoint, body, false);

                try {
                    String raw = endpoint.webClient().post()
                            .uri("/v1/chat/completions")
                            .bodyValue(body)
                            .retrieve()
                            .onStatus(status -> status.isError(), response ->
                                    response.bodyToMono(String.class)
                                            .defaultIfEmpty("")
                                            .map(errorBody -> {
                                                log.error("LLM API 错误: provider={}, status={}, body={}",
                                                        endpoint.provider(), response.statusCode(), errorBody);
                                                return new LlmProviderException(endpoint.provider(),
                                                        response.statusCode().value(), errorBody);
                                            }))
                            .bodyToMono(String.class)
                            .block(Duration.ofMinutes(5));

                    endpoint.circuit().recordSuccess();
                    return parseResponse(endpoint, raw);
                } catch (Exception e) {
                    String error = summarizeError(endpoint, e);
                    errors.add(error);
                    log.warn("LLM provider 调用失败: {}, attempt={}/{}", error, attempt + 1, maxRetries + 1);
                    if (attempt < maxRetries && isRetryable(e)) {
                        sleepBeforeRetry(attempt);
                        continue;
                    }
                    if (isRetryable(e)) {
                        endpoint.circuit().recordFailure(circuitFailureThreshold, circuitRecoveryTimeout);
                    }
                    logFallback(endpoint, nextEndpoint(endpoint));
                    break;
                }
            }
        }

        String message = buildAllProvidersFailedMessage(errors);
        log.error(message);
        throw new RuntimeException(message);
    }

    @Override
    public Flux<ChatStreamDelta> streamChat(List<Message> messages, List<ToolDefinition> tools) {
        return streamWithFallback(0, messages, tools, new ArrayList<>());
    }

    private Flux<ChatStreamDelta> streamWithFallback(int endpointIndex, List<Message> messages,
                                                     List<ToolDefinition> tools, List<String> errors) {
        if (endpointIndex >= endpoints.size()) {
            String message = buildAllProvidersFailedMessage(errors);
            log.error(message);
            return Flux.error(new RuntimeException(message));
        }

        var endpoint = endpoints.get(endpointIndex);
        if (isCircuitOpen(endpoint)) {
            String error = endpoint.provider() + "(circuit_open)";
            errors.add(error);
            var next = endpointName(endpointIndex + 1);
            log.warn("LLM stream provider 熔断中，跳过: provider={}, next={}, remainingMs={}",
                    endpoint.provider(), next, endpoint.circuit().remainingOpenMs());
            return Flux.concat(
                    Flux.just(ChatStreamDelta.providerEvent(
                            LlmProviderEvent.circuitOpen(endpoint.provider(), next, endpoint.circuit().remainingOpenMs()))),
                    streamWithFallback(endpointIndex + 1, messages, tools, errors)
            );
        }

        return streamEndpointAttempt(endpoint, messages, tools, 0)
                .doOnComplete(() -> endpoint.circuit().recordSuccess())
                .onErrorResume(e -> {
                    String error = summarizeError(endpoint, e);
                    errors.add(error);
                    if (isRetryable(e)) {
                        endpoint.circuit().recordFailure(circuitFailureThreshold, circuitRecoveryTimeout);
                    }
                    var next = endpointName(endpointIndex + 1);
                    log.warn("LLM stream provider 失败，准备降级: {}, next={}", error, next);
                    return Flux.concat(
                            Flux.just(ChatStreamDelta.providerEvent(
                                    LlmProviderEvent.fallback(endpoint.provider(), next, error))),
                            streamWithFallback(endpointIndex + 1, messages, tools, errors)
                    );
                });
    }

    private Flux<ChatStreamDelta> streamEndpointAttempt(LlmEndpoint endpoint, List<Message> messages,
                                                        List<ToolDefinition> tools, int attempt) {
        return Flux.defer(() -> streamOnce(endpoint, messages, tools))
                .onErrorResume(e -> {
                    if (attempt < maxRetries && isRetryable(e)) {
                        long waitMs = retryDelayMs(attempt);
                        String reason = summarizeError(endpoint, e);
                        log.warn("LLM stream 重试: provider={}, attempt={}/{}, waitMs={}, error={}",
                                endpoint.provider(), attempt + 1, maxRetries, waitMs, e.getMessage());
                        return Flux.concat(
                                Flux.just(ChatStreamDelta.providerEvent(
                                        LlmProviderEvent.retry(endpoint.provider(), attempt + 1, maxRetries, waitMs, reason))),
                                Mono.delay(Duration.ofMillis(waitMs))
                                        .thenMany(streamEndpointAttempt(endpoint, messages, tools, attempt + 1))
                        );
                    }
                    return Flux.error(e);
                });
    }

    private Flux<ChatStreamDelta> streamOnce(LlmEndpoint endpoint, List<Message> messages, List<ToolDefinition> tools) {
        var body = buildRequestBody(endpoint, messages, tools, true);
        log.debug("LLM stream 请求: provider={}, {} 条消息, {} 个工具, 模型={}", endpoint.provider(), messages.size(),
                tools != null ? tools.size() : 0, endpoint.model());
        logRequestBody(endpoint, body, true);

        return endpoint.webClient().post()
                .uri("/v1/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(errorBody -> {
                                    log.error("LLM stream API 错误: provider={}, status={}, body={}",
                                            endpoint.provider(), response.statusCode(), errorBody);
                                    return new LlmProviderException(endpoint.provider(),
                                            response.statusCode().value(), errorBody);
                                }))
                .bodyToFlux(SSE_TYPE)
                .map(ServerSentEvent::data)
                .filter(data -> data != null && !data.isBlank())
                .map(this::parseStreamData);
    }

    private ObjectNode buildRequestBody(LlmEndpoint endpoint, List<Message> messages, List<ToolDefinition> tools,
                                        boolean stream) {
        var body = objectMapper.createObjectNode();
        body.put("model", endpoint.model());
        body.put("temperature", config.temperature());
        body.put("max_tokens", config.maxTokens());
        //流式输出
        if (stream) {
            body.put("stream", true);
            body.putObject("stream_options").put("include_usage", true);
        }

        // messages
        var msgArray = body.putArray("messages");
        for (var msg : messages) {
            var msgNode = msgArray.addObject();
            msgNode.put("role", msg.role());
            if (msg.content() != null) {
                msgNode.put("content", msg.content());
            }
            if (msg.reasoningContent() != null && !msg.reasoningContent().isBlank()) {
                msgNode.put("reasoning_content", msg.reasoningContent());
            }
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                var tcArray = msgNode.putArray("tool_calls");
                for (var tc : msg.toolCalls()) {
                    var tcNode = tcArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    var funcNode = tcNode.putObject("function");
                    funcNode.put("name", tc.name());
                    funcNode.put("arguments", tc.arguments());
                }
            }
            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        // tools
        if (tools != null && !tools.isEmpty()) {
            var toolsArray = body.putArray("tools");
            for (var td : tools) {
                //转换为JOSN格式
                toolsArray.addPOJO(td.toOpenAiFormat());
            }
        }

        return body;
    }

    private void logRequestBody(LlmEndpoint endpoint, ObjectNode body, boolean stream) {
        if (!config.logRequestBody()) {
            return;
        }
        String mode = stream ? "stream" : "chat";
        log.info("LLM {} 请求体 JSON: provider={}\n{}", mode, endpoint.provider(), body.toPrettyString());
    }

    private ChatStreamDelta parseStreamData(String data) {
        if ("[DONE]".equals(data.strip())) {
            return ChatStreamDelta.doneEvent();
        }
        try {
            var root = objectMapper.readTree(data);
            ChatResponse.TokenUsage usage = parseUsage(root.path("usage"));
            var choice = root.path("choices").isArray() && !root.path("choices").isEmpty()
                    ? root.path("choices").get(0)
                    : null;
            if (choice == null) {
                return new ChatStreamDelta(null, null, List.of(), null, usage, false, null);
            }

            String finishReason = choice.path("finish_reason").isMissingNode()
                    || choice.path("finish_reason").isNull()
                    ? null
                    : choice.path("finish_reason").asText();
            var delta = choice.path("delta");
            String content = delta.has("content") && !delta.path("content").isNull()
                    ? delta.path("content").asText()
                    : null;
            String reasoning = delta.has("reasoning_content") && !delta.path("reasoning_content").isNull()
                    ? delta.path("reasoning_content").asText()
                    : null;

            var toolDeltas = new ArrayList<ChatStreamDelta.ToolCallDelta>();
            var toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (var tc : toolCalls) {
                    int index = tc.path("index").asInt(0);
                    String id = tc.has("id") && !tc.path("id").isNull() ? tc.path("id").asText() : null;
                    var function = tc.path("function");
                    String name = function.has("name") && !function.path("name").isNull()
                            ? function.path("name").asText()
                            : null;
                    String arguments = function.has("arguments") && !function.path("arguments").isNull()
                            ? function.path("arguments").asText()
                            : null;
                    toolDeltas.add(new ChatStreamDelta.ToolCallDelta(index, id, name, arguments));
                }
            }
            return new ChatStreamDelta(content, reasoning, toolDeltas, finishReason, usage, false, null);
        } catch (Exception e) {
            throw new RuntimeException("解析 LLM stream 响应失败: " + e.getMessage(), e);
        }
    }

    private ChatResponse parseResponse(LlmEndpoint endpoint, String raw) throws Exception {
        var root = objectMapper.readTree(raw);
        var choice = root.path("choices").get(0);

        var messageNode = choice.path("message");

        // content
        String content = messageNode.path("content").asText();

        // reasoning_content (thinking mode)
        String reasoningContent = messageNode.has("reasoning_content")
                ? messageNode.path("reasoning_content").asText() : null;

        // tool calls
        List<ToolCall> toolCalls = null;
        var tcNode = messageNode.path("tool_calls");
        if (tcNode.isArray() && !tcNode.isEmpty()) {
            toolCalls = new ArrayList<>();
            for (var tc : tcNode) {
                String id = tc.path("id").asText();
                String name = tc.path("function").path("name").asText();
                String arguments = tc.path("function").path("arguments").asText();
                toolCalls.add(new ToolCall(id, name, arguments));
            }
        }

        String finishReason = choice.path("finish_reason").asText("stop");

        // usage
        var usage = parseUsage(root.path("usage"));

        log.debug("LLM 响应: provider={}, finish={}, content长度={}, toolCalls={}, reasoning={}, tokens={}",
                endpoint.provider(), finishReason, content != null ? content.length() : 0,
                toolCalls != null ? toolCalls.size() : 0,
                reasoningContent != null ? reasoningContent.length() : 0,
                usage.totalTokens());

        return new ChatResponse(content, toolCalls, finishReason, usage, reasoningContent);
    }

    private ChatResponse.TokenUsage parseUsage(tools.jackson.databind.JsonNode usageNode) {
        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
            return new ChatResponse.TokenUsage(0, 0, 0);
        }
        return new ChatResponse.TokenUsage(
                usageNode.path("prompt_tokens").asInt(0),
                usageNode.path("completion_tokens").asInt(0),
                usageNode.path("total_tokens").asInt(0)
        );
    }

    private List<LlmEndpoint> buildEndpoints(JarvisConfig.LLMConfig config, WebClient.Builder builder) {
        var result = new ArrayList<LlmEndpoint>();
        result.add(newEndpoint(
                hasText(config.provider()) ? config.provider() : "primary",
                config.apiBase(),
                config.apiKey(),
                config.model(),
                builder));

        var fallbacks = config.fallbackProviders();
        if (fallbacks != null) {
            for (var fallback : fallbacks) {
                if (fallback == null || !fallback.enabled()) {
                    continue;
                }
                if (!hasText(fallback.apiBase()) || !hasText(fallback.model()) || !hasText(fallback.apiKey())) {
                    log.warn("跳过 LLM fallback provider: provider={}, reason=missing api-base/model/api-key",
                            fallback.provider());
                    continue;
                }
                result.add(newEndpoint(fallback.provider(), fallback.apiBase(), fallback.apiKey(), fallback.model(), builder));
            }
        }
        return List.copyOf(result);
    }

    private LlmEndpoint newEndpoint(String provider, String apiBase, String apiKey, String model,
                                    WebClient.Builder builder) {
        var clientBuilder = builder.clone()
                .baseUrl(stripTrailingSlash(apiBase))
                .defaultHeader("Content-Type", "application/json");
        if (hasText(apiKey)) {
            clientBuilder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        return new LlmEndpoint(provider, model, clientBuilder.build(), new CircuitState());
    }

    private boolean isRetryable(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof LlmProviderException lpe) {
                int status = lpe.statusCode();
                return status == 0 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
            }
            current = current.getCause();
        }
        return true;
    }

    private void sleepBeforeRetry(int attempt) {
        long delay = retryDelayMs(attempt);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long retryDelayMs(int attempt) {
        return Math.min(
                maxBackoff.toMillis(),
                initialBackoff.toMillis() * (1L << Math.min(attempt, 10))
        );
    }

    private String summarizeError(LlmEndpoint endpoint, Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof LlmProviderException lpe) {
                return endpoint.provider() + "(status=" + lpe.statusCode() + ", body=" + trim(lpe.responseBody(), 300) + ")";
            }
            current = current.getCause();
        }
        return endpoint.provider() + "(" + trim(e.getMessage(), 300) + ")";
    }

    private String buildAllProvidersFailedMessage(List<String> errors) {
        return "所有 LLM 提供商调用失败，请稍后重试。失败详情: " + String.join(" | ", errors);
    }

    private boolean isCircuitOpen(LlmEndpoint endpoint) {
        return circuitBreakerEnabled && endpoint.circuit().isOpen();
    }

    private void logFallback(LlmEndpoint current, LlmEndpoint next) {
        if (next == null) {
            log.warn("LLM provider 已耗尽重试且没有可用降级 provider: from={}", current.provider());
            return;
        }
        log.warn("LLM provider 已耗尽重试，切换到下一个 provider: from={}, next={}",
                current.provider(), next.provider());
    }

    private LlmEndpoint nextEndpoint(LlmEndpoint endpoint) {
        int index = endpoints.indexOf(endpoint);
        if (index < 0 || index + 1 >= endpoints.size()) {
            return null;
        }
        return endpoints.get(index + 1);
    }

    private String endpointName(int index) {
        if (index < 0 || index >= endpoints.size()) {
            return "";
        }
        return endpoints.get(index).provider();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private record LlmEndpoint(
            String provider,
            String model,
            WebClient webClient,
            CircuitState circuit
    ) {}

    private static final class CircuitState {
        private int failureCount;
        private long openUntilMs;

        synchronized boolean isOpen() {
            long now = System.currentTimeMillis();
            if (openUntilMs <= now) {
                return false;
            }
            return true;
        }

        synchronized long remainingOpenMs() {
            return Math.max(0, openUntilMs - System.currentTimeMillis());
        }

        synchronized void recordSuccess() {
            failureCount = 0;
            openUntilMs = 0;
        }

        synchronized void recordFailure(int threshold, Duration recoveryTimeout) {
            failureCount++;
            if (failureCount >= threshold) {
                openUntilMs = System.currentTimeMillis() + recoveryTimeout.toMillis();
            }
        }
    }
}
