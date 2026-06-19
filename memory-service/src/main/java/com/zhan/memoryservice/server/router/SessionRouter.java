package com.zhan.memoryservice.server.router;

import com.zhan.memoryservice.session.SessionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.NoSuchElementException;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Configuration
public class SessionRouter {

    private final SessionService sessionService;

    public SessionRouter(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Bean
    public RouterFunction<ServerResponse> sessionRoutes() {
        return route()
                .POST("/api/v1/session/create", this::handleCreate)
                .POST("/api/v1/session/message", this::handleAddMessage)
                .POST("/api/v1/session/commit", this::handleCommit)
                .GET("/api/v1/session/list", this::handleList)
                .GET("/api/v1/session/{id}/messages", this::handleMessages)
                .GET("/api/v1/session/{id}/context", this::handleContext)
                .build();
    }

    private Mono<ServerResponse> handleCreate(ServerRequest req) {
        return req.bodyToMono(Map.class)
                .flatMap(body -> {
                    String sessionId = (String) body.getOrDefault("session_id",
                            "session_" + System.currentTimeMillis());
                    String ownerUserId = (String) body.getOrDefault("owner_user_id", "");
                    var session = sessionService.createSession(sessionId, ownerUserId);
                    return ServerResponse.ok().bodyValue(Map.of(
                            "session_id", session.sessionId(),
                            "owner_user_id", session.ownerUserId() != null ? session.ownerUserId() : "",
                            "title", session.title() != null ? session.title() : "",
                            "created_at", session.createdAt().toString()
                    ));
                });
    }

    private Mono<ServerResponse> handleAddMessage(ServerRequest req) {
        return req.bodyToMono(Map.class)
                .flatMap(body -> Mono.fromCallable(() -> {
                    String sessionId = (String) body.get("session_id");
                    String role = (String) body.getOrDefault("role", "user");
                    String text = (String) body.get("text");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metadata = body.get("metadata") instanceof Map<?, ?> m
                            ? (Map<String, Object>) m
                            : Map.of();
                    var msg = sessionService.addMessage(sessionId, role, text, metadata);
                    return Map.of("message_id", msg.id(), "role", msg.role());
                }).subscribeOn(Schedulers.boundedElastic())
                  .flatMap(result -> ServerResponse.ok().bodyValue(result)));
    }

    private Mono<ServerResponse> handleCommit(ServerRequest req) {
        return req.bodyToMono(Map.class)
                .flatMap(body -> Mono.fromCallable(() -> {
                    String sessionId = (String) body.get("session_id");
                    int keepRecent = body.get("keep_recent_count") instanceof Number n
                            ? n.intValue() : -1;
                    if (keepRecent >= 0) {
                        return sessionService.commit(sessionId, keepRecent);
                    }
                    return sessionService.commitAsync(sessionId);
                }).subscribeOn(Schedulers.boundedElastic())
                  .flatMap(result -> ServerResponse.ok().bodyValue(result)));
    }

    private Mono<ServerResponse> handleList(ServerRequest req) {
        String ownerUserId = req.queryParam("owner_user_id").orElse("");
        return Mono.fromCallable(() -> sessionService.listSessions(ownerUserId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> ServerResponse.ok().bodyValue(Map.of("sessions", result)));
    }

    private Mono<ServerResponse> handleMessages(ServerRequest req) {
        String sessionId = req.pathVariable("id");
        int maxMessages = req.queryParam("max_messages")
                .map(Integer::parseInt)
                .orElse(0);
        return Mono.fromCallable(() -> sessionService.getSessionMessages(sessionId, maxMessages))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> ServerResponse.ok().bodyValue(result))
                .onErrorResume(NoSuchElementException.class, e ->
                        ServerResponse.notFound().build());
    }

    private Mono<ServerResponse> handleContext(ServerRequest req) {
        String sessionId = req.pathVariable("id");
        int maxMessages = req.queryParam("max_messages")
                .map(Integer::parseInt)
                .orElse(40);  // 默认 40 条（20 轮对话）
        return Mono.fromCallable(() -> sessionService.getSessionContext(sessionId, maxMessages))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }
}
