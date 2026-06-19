package com.zhan.jarvis.server.router;

import com.zhan.jarvis.auth.AuthWebFilter;
import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.config.JarvisConfig;
import com.zhan.jarvis.hook.HookDecisionException;
import com.zhan.jarvis.hook.impl.GitPolicyHook;
import com.zhan.jarvis.permission.PendingToolPermission;
import com.zhan.jarvis.permission.ToolPermissionManager;
import com.zhan.jarvis.tool.ToolContext;
import com.zhan.jarvis.tool.ToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * 通用工具人工确认入口。
 * confirm 不暴露给 LLM，只能由已登录用户或后续可信 channel 入口触发。
 */
@Configuration
public class ToolConfirmationRouter {

    private final ToolRegistry toolRegistry;
    private final ToolPermissionManager permissionManager;
    private final JarvisConfig config;

    public ToolConfirmationRouter(ToolRegistry toolRegistry, ToolPermissionManager permissionManager,
                                  JarvisConfig config) {
        this.toolRegistry = toolRegistry;
        this.permissionManager = permissionManager;
        this.config = config;
    }

    @Bean
    public RouterFunction<ServerResponse> toolConfirmationRoute() {
        return route(POST("/api/v1/tools/confirm"), this::handleConfirm)
                .andRoute(POST("/api/v1/git/confirm"), this::handleConfirm);
    }

    private Mono<ServerResponse> handleConfirm(ServerRequest req) {
        String userId = authenticatedUserId(req);
        if (userId.isBlank()) {
            return ServerResponse.status(HttpStatus.UNAUTHORIZED)
                    .bodyValue(Map.of("success", false, "msg", "未登录，不能确认工具操作"));
        }

        return req.bodyToMono(Map.class)
                .flatMap(body -> Mono.fromCallable(() -> confirm(body, userId))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(result -> ServerResponse.ok().bodyValue(result))
                .onErrorResume(HookDecisionException.class, e ->
                        ServerResponse.status(HttpStatus.FORBIDDEN)
                                .bodyValue(Map.of("success", false, "msg", e.getMessage())))
                .onErrorResume(IllegalArgumentException.class, e ->
                        ServerResponse.status(HttpStatus.BAD_REQUEST)
                                .bodyValue(Map.of("success", false, "msg", e.getMessage())))
                .onErrorResume(Exception.class, e ->
                        ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .bodyValue(Map.of("success", false, "msg", e.getMessage())));
    }

    private Map<String, Object> confirm(Map<?, ?> body, String userId) {
        String confirmId = confirmId(body);
        if (confirmId.isBlank()) {
            throw new IllegalArgumentException("confirm_id 不能为空");
        }

        PendingToolPermission pending = permissionManager.take(confirmId);
        var metadata = new LinkedHashMap<String, Object>(pending.metadata() == null ? Map.of() : pending.metadata());
        metadata.put(GitPolicyHook.META_HUMAN_CONFIRMED, true);
        metadata.put(ToolPermissionManager.META_CONFIRM_ID, confirmId);
        metadata.put("confirmed_by", userId);
        metadata.put("confirm_source", "http_api");

        var ctx = new ToolContext(
                pending.sessionId() != null ? pending.sessionId() : "tool-confirm:" + confirmId,
                pending.sessionKey() != null
                        ? pending.sessionKey()
                        : new SessionKey("http", "tool-confirm", "tool-confirm"),
                pending.workspaceDir() != null && !pending.workspaceDir().isBlank()
                        ? pending.workspaceDir()
                        : config.agent().workspace(),
                userId,
                metadata
        );
        String result = toolRegistry.executeTool(pending.toolName(), pending.arguments(), ctx);
        return Map.of(
                "success", true,
                "confirm_id", confirmId,
                "tool", pending.toolName(),
                "summary", pending.summary(),
                "result", result
        );
    }

    private String confirmId(Map<?, ?> body) {
        if (body == null) {
            return "";
        }
        Object snake = body.get("confirm_id");
        if (snake != null && !String.valueOf(snake).isBlank()) {
            return String.valueOf(snake).trim();
        }
        Object camel = body.get("confirmId");
        return camel == null ? "" : String.valueOf(camel).trim();
    }

    private String authenticatedUserId(ServerRequest req) {
        Object username = req.exchange().getAttribute(AuthWebFilter.ATTR_USERNAME);
        if (username != null && !String.valueOf(username).isBlank()) {
            return String.valueOf(username);
        }
        Object userId = req.exchange().getAttribute(AuthWebFilter.ATTR_USER_ID);
        return userId == null ? "" : String.valueOf(userId);
    }
}
