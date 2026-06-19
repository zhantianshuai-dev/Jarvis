package com.zhan.jarvis.server.router;

import cn.hutool.core.util.IdUtil;
import com.zhan.jarvis.agent.AgentLoop;
import com.zhan.jarvis.auth.AuthWebFilter;
import com.zhan.jarvis.channel.HttpChannel;
import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.server.sse.SseEventHub;
import com.zhan.jarvis.server.sse.SseEventTypes;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * API Router — 对外暴露 HTTP API。
 * <p>
 * WebFlux RouterFunction 风格（非 @RestController）。
 */
@Configuration
public class ChatRouter {

    private static final Logger log = LoggerFactory.getLogger(ChatRouter.class);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(10);

    private final HttpChannel httpChannel;
    private final AgentLoop agentLoop;
    private final SseEventHub sseEventHub;

    public ChatRouter(HttpChannel httpChannel, AgentLoop agentLoop, SseEventHub sseEventHub) {
        this.httpChannel = httpChannel;
        this.agentLoop = agentLoop;
        this.sseEventHub = sseEventHub;
    }

    @Bean
    public RouterFunction<ServerResponse> chatRoute() {
        return route(POST("/api/v1/chat"), this::handleChat)
                .andRoute(POST("/api/v1/chat/stream"), this::handleChatStream)
                .andRoute(GET("/api/v1/events"), this::handleEvents)
                .andRoute(GET("/api/v1/health"), this::handleHealth);
    }

    /**
     * POST /api/v1/chat — 发送消息获取回复。
     * <p>
     * Agent 循环运行在独立 Virtual Thread 上，不受客户端断开影响。
     * 即使客户端超时断开，Agent 仍会在后台继续执行直到完成。
     */
    private Mono<ServerResponse> handleChat(ServerRequest req) {
        //读取请求体，并反序列化为Java类型
        return req.bodyToMono(ChatRequest.class)
                .flatMap(cr -> Mono.fromCallable(() -> {
                    String sessionId = cr.sessionId() != null && !cr.sessionId().isBlank()
                            ? cr.sessionId()
                            : "session_" + IdUtil.getSnowflake(1, 1).nextId();
                    String userId = authenticatedUserId(req, cr.userId());
                    String messageId = "msg_" + IdUtil.getSnowflake(1, 1).nextId();
                    log.info("收到消息: sessionId={}, message={}", sessionId, cr.message());

                    var outbound = httpChannel.submitAndAwait(messageId, sessionId, userId,
                            cr.message(), RESPONSE_TIMEOUT);
                    return Map.<String, Object>of(
                            "session_id", outbound.sessionId(),
                            "reply", outbound.content()
                    );
                }).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(body -> ServerResponse.ok().bodyValue(body)); //这里就是异步的，等有结果了再返回ok
    }

    private Mono<ServerResponse> handleChatStream(ServerRequest req) {
        return req.bodyToMono(ChatRequest.class)
                .flatMap(cr -> {
                    String sessionId = cr.sessionId() != null && !cr.sessionId().isBlank()
                            ? cr.sessionId()
                            : "session_" + IdUtil.getSnowflake(1, 1).nextId();
                    String userId = authenticatedUserId(req, cr.userId());
                    var sessionKey = new SessionKey("http", "default", sessionId);
                    log.info("收到流式消息: sessionId={}, message={}", sessionId, cr.message());

                    var events = agentLoop.runStreaming(sessionKey, sessionId, cr.message(), userId)
                            .map(this::toServerSentEvent);
                    return ServerResponse.ok()
                            .contentType(MediaType.TEXT_EVENT_STREAM)
                            .body(events, ServerSentEvent.class);
                });
    }

    private Mono<ServerResponse> handleEvents(ServerRequest req) {
        String sessionId = req.queryParam("session_id")
                .filter(s -> !s.isBlank())
                .orElse("default");
        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(sseEventHub.subscribe(sessionId), ServerSentEvent.class);
    }

    private ServerSentEvent<Map<String, Object>> toServerSentEvent(Map<String, Object> data) {
        Object type = data.getOrDefault("type", SseEventTypes.MESSAGE);
        return ServerSentEvent.<Map<String, Object>>builder()
                .event(String.valueOf(type))
                .data(new LinkedHashMap<>(data))
                .build();
    }

    private String authenticatedUserId(ServerRequest req, String fallback) {
        Object authUser = req.exchange().getAttribute(AuthWebFilter.ATTR_USERNAME);
        if (authUser != null && !String.valueOf(authUser).isBlank()) {
            return String.valueOf(authUser);
        }
        return fallback != null ? fallback : "anonymous";
    }

    /**
     * GET /api/v1/health — 健康检查。
     */
    private Mono<ServerResponse> handleHealth(ServerRequest req) {
        return ServerResponse.ok().bodyValue(Map.of("status", "ok", "service", "Jarvis"));
    }
}
