package com.zhan.jarvis.auth;

import com.zhan.jarvis.config.JarvisConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Configuration
public class AuthRouter {

    private final JarvisConfig config;
    private final AuthService authService;

    public AuthRouter(JarvisConfig config, org.springframework.beans.factory.ObjectProvider<AuthService> authService) {
        this.config = config;
        // AuthService 在 jarvis.auth.enabled=false 时不会创建，所以这里用 ObjectProvider 做可选注入。
        this.authService = authService.getIfAvailable();
    }

    @Bean
    public RouterFunction<ServerResponse> authRoute() {
        // WebFlux 函数式路由：这里负责 HTTP 协议层，具体账号逻辑放在 AuthService。
        return route(POST("/api/v1/auth/login"), this::handleLogin)
                .andRoute(POST("/api/v1/auth/register"), this::handleRegister)
                .andRoute(GET("/api/v1/auth/me"), this::handleMe)
                .andRoute(POST("/api/v1/auth/logout"), this::handleLogout)
                .andRoute(POST("/api/v1/auth/change-password"), this::handleChangePassword);
    }

    private Mono<ServerResponse> handleLogin(ServerRequest req) {
        if (!authAvailable()) {
            return authDisabled();
        }
        return req.bodyToMono(AuthModels.LoginRequest.class)
                .flatMap(body -> Mono.fromCallable(() ->
                                authService.login(body.username(), body.password(), clientIp(req)))
                        // BCrypt 和 JDBC 都是阻塞操作，不能占用 Netty event-loop。
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(body -> ServerResponse.ok().bodyValue(body))
                .onErrorResume(AuthService.AuthException.class, e ->
                        ServerResponse.status(HttpStatus.UNAUTHORIZED)
                                .bodyValue(Map.of("code", 401, "msg", e.getMessage(), "success", false)));
    }

    private Mono<ServerResponse> handleRegister(ServerRequest req) {
        if (!authAvailable()) {
            return authDisabled();
        }
        return req.bodyToMono(AuthModels.RegisterRequest.class)
                .flatMap(body -> Mono.fromCallable(() ->
                                authService.register(body.username(), body.password(), body.displayName()))
                        // 注册会写 PostgreSQL 并计算 BCrypt 哈希，放到 boundedElastic 执行。
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(body -> ServerResponse.status(HttpStatus.CREATED).bodyValue(body))
                .onErrorResume(AuthService.AuthException.class, e ->
                        ServerResponse.status(HttpStatus.BAD_REQUEST)
                                .bodyValue(Map.of("code", 400, "msg", e.getMessage(), "success", false)));
    }

    private Mono<ServerResponse> handleMe(ServerRequest req) {
        if (!authAvailable()) {
            return authDisabled();
        }
        return Mono.fromCallable(() -> authService.me(resolveToken(req)))
                // verifyToken 会访问 Sa-Token 存储和 PostgreSQL 用户表。
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(body -> ServerResponse.ok().bodyValue(body))
                .onErrorResume(Exception.class, e ->
                        ServerResponse.status(HttpStatus.UNAUTHORIZED)
                                .bodyValue(Map.of("code", 401, "msg", e.getMessage(), "success", false)));
    }

    private Mono<ServerResponse> handleLogout(ServerRequest req) {
        if (!authAvailable()) {
            return authDisabled();
        }
        return Mono.fromRunnable(() -> authService.logout(resolveToken(req)))
                // 保持与其它认证接口一致，避免阻塞 event-loop。
                .subscribeOn(Schedulers.boundedElastic())
                .then(ServerResponse.ok().bodyValue(Map.of("success", true)))
                .onErrorResume(Exception.class, e ->
                        ServerResponse.status(HttpStatus.UNAUTHORIZED)
                                .bodyValue(Map.of("code", 401, "msg", e.getMessage(), "success", false)));
    }

    private Mono<ServerResponse> handleChangePassword(ServerRequest req) {
        if (!authAvailable()) {
            return authDisabled();
        }
        String token = resolveToken(req);
        return req.bodyToMono(AuthModels.ChangePasswordRequest.class)
                .flatMap(body -> Mono.fromRunnable(() ->
                                authService.changePassword(token, body.oldPassword(), body.newPassword()))
                        // 改密包含 BCrypt 和数据库更新。
                        .subscribeOn(Schedulers.boundedElastic()))
                .then(ServerResponse.ok().bodyValue(Map.of("success", true)))
                .onErrorResume(AuthService.AuthException.class, e ->
                        ServerResponse.status(HttpStatus.BAD_REQUEST)
                                .bodyValue(Map.of("code", 400, "msg", e.getMessage(), "success", false)))
                .onErrorResume(Exception.class, e ->
                        ServerResponse.status(HttpStatus.UNAUTHORIZED)
                                .bodyValue(Map.of("code", 401, "msg", e.getMessage(), "success", false)));
    }

    private boolean authAvailable() {
        // 配置关闭认证时，认证路由返回 404，避免暴露半可用的登录接口。
        return config.auth() != null && config.auth().enabled() && authService != null;
    }

    private Mono<ServerResponse> authDisabled() {
        return ServerResponse.status(HttpStatus.NOT_FOUND)
                .bodyValue(Map.of("code", 404, "msg", "auth disabled", "success", false));
    }

    private String resolveToken(ServerRequest req) {
        // 主路径使用 Authorization: Bearer；query 参数用于 SSE/EventSource 等不方便带 header 的场景。
        String header = req.headers().firstHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && !header.isBlank()) {
            String value = header.strip();
            if (value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
                return value.substring("Bearer ".length()).strip();
            }
            return value;
        }
        return req.queryParam("access_token").orElse("");
    }

    private String clientIp(ServerRequest req) {
        // 反向代理部署时优先读取 X-Forwarded-For 的第一个 IP。
        String forwardedFor = req.headers().firstHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].strip();
        }
        return req.remoteAddress()
                .map(address -> address.getAddress() != null
                        ? address.getAddress().getHostAddress()
                        : address.getHostString())
                .orElse("");
    }
}
