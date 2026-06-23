package com.zhan.jarvis.llm;

import com.zhan.jarvis.config.JarvisConfig;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final JarvisConfig.LLMConfig config;

    public OpenAiAgentLLMProvider(JarvisConfig.LLMConfig config, ObjectMapper objectMapper,
                                   WebClient.Builder builder) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.webClient = builder
                .baseUrl(stripTrailingSlash(config.apiBase()))
                .defaultHeader("Authorization", "Bearer " + config.apiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        var body = buildRequestBody(messages, tools, false);

        log.debug("LLM 请求: {} 条消息, {} 个工具, 模型={}", messages.size(),
                tools != null ? tools.size() : 0, config.model());
        logRequestBody(body, false);

        String raw;
        try {
            raw = webClient.post()
                    .uri("/v1/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(status -> status.isError(), response -> {
                        String errorBody = response.bodyToMono(String.class).block(Duration.ofSeconds(30));
                        log.error("LLM API 错误: status={}, body={}", response.statusCode(), errorBody);
                        return response.createException();
                    })
                    .bodyToMono(String.class)
                    .block(Duration.ofMinutes(5));
        } catch (Exception e) {
            log.error("LLM API 调用失败: {}", e.getMessage());
            log.debug("LLM API 调用失败，请求体: {}", body.toPrettyString());
            throw e;
        }

        try {
            return parseResponse(raw);
        } catch (Exception e) {
            log.error("解析 LLM 响应失败: {}", raw, e);
            throw new RuntimeException("解析 LLM 响应失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<ChatStreamDelta> streamChat(List<Message> messages, List<ToolDefinition> tools) {
        var body = buildRequestBody(messages, tools, true);
        log.debug("LLM stream 请求: {} 条消息, {} 个工具, 模型={}", messages.size(),
                tools != null ? tools.size() : 0, config.model());
        logRequestBody(body, true);

        return webClient.post()
                .uri("/v1/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(errorBody -> {
                                    log.error("LLM stream API 错误: status={}, body={}",
                                            response.statusCode(), errorBody);
                                    return new RuntimeException("LLM stream API 错误: " + response.statusCode());
                                }))
                .bodyToFlux(SSE_TYPE)
                .map(ServerSentEvent::data)
                .filter(data -> data != null && !data.isBlank())
                .map(this::parseStreamData);
    }

    private ObjectNode buildRequestBody(List<Message> messages, List<ToolDefinition> tools, boolean stream) {
        var body = objectMapper.createObjectNode();
        body.put("model", config.model());
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

    private void logRequestBody(ObjectNode body, boolean stream) {
        if (!config.logRequestBody()) {
            return;
        }
        String mode = stream ? "stream" : "chat";
        log.info("LLM {} 请求体 JSON:\n{}", mode, body.toPrettyString());
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
                return new ChatStreamDelta(null, null, List.of(), null, usage, false);
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
            return new ChatStreamDelta(content, reasoning, toolDeltas, finishReason, usage, false);
        } catch (Exception e) {
            throw new RuntimeException("解析 LLM stream 响应失败: " + e.getMessage(), e);
        }
    }

    private ChatResponse parseResponse(String raw) throws Exception {
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

        log.debug("LLM 响应: finish={}, content长度={}, toolCalls={}, reasoning={}, tokens={}",
                finishReason, content != null ? content.length() : 0,
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

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
