package com.zhan.jarvis.server.router;

import cn.hutool.core.util.IdUtil;
import com.zhan.jarvis.auth.AuthWebFilter;
import com.zhan.jarvis.memory.MemoryServiceClient;
import com.zhan.jarvis.session.SessionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * Chat 会话历史 API。
 * 前端只访问 Jarvis，由 Jarvis 按当前登录用户向 memory-service 读写会话。
 */
@Configuration
public class ChatSessionRouter {

    private final SessionManager sessionManager;

    public ChatSessionRouter(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Bean
    public RouterFunction<ServerResponse> chatSessionRoute() {
        return route(GET("/api/v1/chat/sessions"), this::handleList)
                .andRoute(POST("/api/v1/chat/sessions"), this::handleCreate)
                .andRoute(GET("/api/v1/chat/sessions/{sessionId}/messages"), this::handleMessages);
    }

    private Mono<ServerResponse> handleList(ServerRequest req) {
        String userId = currentUserId(req);
        return Mono.fromCallable(() -> sessionManager.listSessions(userId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(sessions -> ServerResponse.ok().bodyValue(Map.of(
                        "sessions", sessions.stream().map(this::sessionView).toList()
                )));
    }

    private Mono<ServerResponse> handleCreate(ServerRequest req) {
        String userId = currentUserId(req);
        return Mono.fromCallable(() -> {
                    String sessionId = "web_" + userId + "_" + IdUtil.fastSimpleUUID();
                    sessionManager.getOrCreate(sessionId, userId);
                    return Map.of(
                            "session_id", sessionId,
                            "title", "新的对话"
                    );
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

    private Mono<ServerResponse> handleMessages(ServerRequest req) {
        String userId = currentUserId(req);
        String sessionId = req.pathVariable("sessionId");
        int maxMessages = req.queryParam("max_messages")
                .map(Integer::parseInt)
                .orElse(0);
        return Mono.fromCallable(() -> sessionManager.getSessionMessages(sessionId, maxMessages))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(messages -> {
                    if (!messages.ownerUserId().isBlank() && !userId.equals(messages.ownerUserId())) {
                        return ServerResponse.status(HttpStatus.FORBIDDEN)
                                .bodyValue(Map.of("code", 403, "msg", "无权访问该会话", "success", false));
                    }
                    return ServerResponse.ok().bodyValue(messagesView(messages));
                })
                .onErrorResume(WebClientResponseException.NotFound.class, e ->
                        ServerResponse.status(HttpStatus.NOT_FOUND)
                                .bodyValue(Map.of("code", 404, "msg", "会话不存在", "success", false)));
    }

    private Map<String, Object> sessionView(MemoryServiceClient.SessionSummary summary) {
        var item = new LinkedHashMap<String, Object>();
        item.put("sessionId", summary.sessionId());
        item.put("title", summary.title());
        item.put("messageCount", summary.messageCount());
        item.put("createdAt", summary.createdAt());
        item.put("updatedAt", summary.updatedAt());
        return item;
    }

    private Map<String, Object> messagesView(MemoryServiceClient.SessionMessages sessionMessages) {
        var body = new LinkedHashMap<String, Object>();
        body.put("sessionId", sessionMessages.sessionId());
        body.put("title", sessionMessages.title());
        body.put("messages", sessionMessages.messages().stream()
                .filter(this::visibleMessage)
                .map(this::messageView)
                .toList());
        return body;
    }

    private boolean visibleMessage(MemoryServiceClient.SessionMessage message) {
        if ("user".equals(message.role())) return true;
        if (!"assistant".equals(message.role())) return false;
        if (message.content() == null || message.content().isBlank()) return false;
        Object finalFlag = message.metadata().get("final");
        return finalFlag == null || Boolean.parseBoolean(String.valueOf(finalFlag));
    }

    private Map<String, Object> messageView(MemoryServiceClient.SessionMessage message) {
        var item = new LinkedHashMap<String, Object>();
        item.put("id", message.id() != null && !message.id().isBlank()
                ? message.id()
                : "msg_" + IdUtil.fastSimpleUUID());
        item.put("role", message.role());
        item.put("content", message.content() != null ? message.content() : "");
        item.put("createdAt", message.createdAt());
        if (message.metadata() != null && !message.metadata().isEmpty()) {
            item.putAll(message.metadata());
        }
        return item;
    }

    private String currentUserId(ServerRequest req) {
        Object userId = req.exchange().getAttribute(AuthWebFilter.ATTR_USER_ID);
        if (userId != null && !String.valueOf(userId).isBlank()) {
            return String.valueOf(userId);
        }
        Object username = req.exchange().getAttribute(AuthWebFilter.ATTR_USERNAME);
        return username != null ? String.valueOf(username) : "anonymous";
    }
}
