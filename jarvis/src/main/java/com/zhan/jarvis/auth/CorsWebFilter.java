package com.zhan.jarvis.auth;

import com.zhan.jarvis.config.JarvisConfig;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorsWebFilter implements WebFilter {

    private static final String DEFAULT_ALLOWED_HEADERS = "Authorization, Content-Type, Accept";
    private static final String DEFAULT_ALLOWED_METHODS = "GET, POST, OPTIONS";

    private final Set<String> allowedOrigins;

    public CorsWebFilter(JarvisConfig config) {
        String[] origins = config.auth() != null ? config.auth().allowedOrigins() : null;
        // allowedOrigins 来自 yaml/env，例：http://127.0.0.1:5180,http://localhost:5173。
        this.allowedOrigins = normalize(origins);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String origin = exchange.getRequest().getHeaders().getOrigin();
        if (origin != null && isAllowed(origin, exchange)) {
            // 只回显白名单内的 Origin，避免把 Access-Control-Allow-Origin 设成任意来源。
            var headers = exchange.getResponse().getHeaders();
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            headers.set(HttpHeaders.VARY, "Origin");
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, DEFAULT_ALLOWED_METHODS);
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, DEFAULT_ALLOWED_HEADERS);
            headers.set(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
        }

        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
            // 浏览器预检请求不应该进入业务路由，也不应该触发 AuthWebFilter 的 token 校验。
            exchange.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private boolean isAllowed(String origin, ServerWebExchange exchange) {
        // 支持 *，但生产环境更建议显式配置可信前端域名。
        return allowedOrigins.contains("*")
                || allowedOrigins.contains(origin)
                || isSameRequestHost(origin, exchange);
    }

    private boolean isSameRequestHost(String origin, ServerWebExchange exchange) {
        try {
            String originHost = URI.create(origin).getHost();
            String requestHost = exchange.getRequest().getHeaders().getHost() != null
                    ? exchange.getRequest().getHeaders().getHost().getHostString()
                    : null;
            return originHost != null && originHost.equalsIgnoreCase(requestHost);
        } catch (Exception e) {
            return false;
        }
    }

    private static Set<String> normalize(String[] values) {
        // 去掉空项和前后空格，保持配置解析结果稳定。
        var result = new LinkedHashSet<String>();
        if (values == null || values.length == 0) {
            return result;
        }
        Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .forEach(result::add);
        return result;
    }
}
