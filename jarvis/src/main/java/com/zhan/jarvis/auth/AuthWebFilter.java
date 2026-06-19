package com.zhan.jarvis.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
@ConditionalOnProperty(prefix = "jarvis.auth", name = "enabled", havingValue = "true")
public class AuthWebFilter implements WebFilter {

    /** 写入 ServerWebExchange attributes，后续 Router/Handler 可以直接读取当前用户。 */
    public static final String ATTR_USER_ID = "auth.userId";
    public static final String ATTR_USERNAME = "auth.username";

    private final AuthService authService;

    public AuthWebFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        // 只保护 API 路径；静态资源、前端页面等不在这里处理。
        if (!path.startsWith("/api/v1/") || isPublicPath(path)) {
            return chain.filter(exchange);
        }

        try {
            String token = resolveToken(exchange);
            AgentUser user = authService.verifyToken(token);
            // 鉴权通过后把用户身份挂到当前 exchange，ChatRouter 会用它覆盖请求体里的 userId。
            exchange.getAttributes().put(ATTR_USER_ID, String.valueOf(user.id()));
            exchange.getAttributes().put(ATTR_USERNAME, user.username());
            return chain.filter(exchange);
        } catch (Exception e) {
            // 不调用 chain.filter 就会中断后续路由处理，直接返回 401。
            return unauthorized(exchange, e.getMessage());
        }
    }

    private boolean isPublicPath(String path) {
        // 登录、注册、健康检查必须放行，否则未登录用户无法拿到 token。
        return "/api/v1/health".equals(path)
                || "/api/v1/auth/login".equals(path)
                || "/api/v1/auth/register".equals(path);
    }

    // 取出token
    private String resolveToken(ServerWebExchange exchange) {
        // 推荐格式：Authorization: Bearer <token>。
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && !header.isBlank()) {
            String value = header.strip();
            if (value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
                return value.substring("Bearer ".length()).strip();
            }
            return value;
        }
        // 兼容无法设置自定义 header 的 SSE 客户端或简单调试场景。
        return exchange.getRequest().getQueryParams().getFirst("access_token");
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        // WebFilter 层没有 ServerResponse，因此直接操作底层 response 写 JSON。
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":401,\"msg\":\"" + escapeJson(message) + "\",\"success\":false}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private String escapeJson(String value) {
        if (value == null || value.isBlank()) {
            return "unauthorized";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
