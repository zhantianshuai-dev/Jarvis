package com.zhan.sandboxservice;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

/**
 * 沙箱 HTTP API。
 */
@Configuration
public class SandboxRouter {

    private final SandboxExecutor executor;

    public SandboxRouter(SandboxExecutor executor) {
        this.executor = executor;
    }

    @Bean
    public RouterFunction<ServerResponse> sandboxRoutes() {
        return route(GET("/health"), req -> ServerResponse.ok().bodyValue(Map.of("status", "ok")))
                .andRoute(POST("/api/v1/sandbox/exec"), this::execute)
                .andRoute(POST("/api/v1/sandbox/read"), this::readFile)
                .andRoute(POST("/api/v1/sandbox/write"), this::writeFile)
                .andRoute(POST("/api/v1/sandbox/list"), this::listDir);
    }

    private Mono<ServerResponse> execute(ServerRequest req) {
        return req.bodyToMono(SandboxRequest.class)
                .flatMap(body -> Mono.fromCallable(() -> executor.execute(body.workspace(), body.command()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(result -> ServerResponse.ok().bodyValue(result))
                .onErrorResume(this::errorResponse);
    }

    private Mono<ServerResponse> readFile(ServerRequest req) {
        return req.bodyToMono(SandboxRequest.class)
                .flatMap(body -> Mono.fromCallable(() -> executor.readFile(body.workspace(), body.path()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(result -> ServerResponse.ok().bodyValue(result))
                .onErrorResume(this::errorResponse);
    }

    private Mono<ServerResponse> writeFile(ServerRequest req) {
        return req.bodyToMono(SandboxRequest.class)
                .flatMap(body -> Mono.fromCallable(() -> executor.writeFile(body.workspace(), body.path(), body.content()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(result -> ServerResponse.ok().bodyValue(result))
                .onErrorResume(this::errorResponse);
    }

    private Mono<ServerResponse> listDir(ServerRequest req) {
        return req.bodyToMono(SandboxRequest.class)
                .flatMap(body -> Mono.fromCallable(() -> executor.listDir(body.workspace(), body.path()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(result -> ServerResponse.ok().bodyValue(result))
                .onErrorResume(this::errorResponse);
    }

    private Mono<ServerResponse> errorResponse(Throwable error) {
        return ServerResponse.status(HttpStatus.BAD_REQUEST)
                .bodyValue(Map.of(
                        "success", false,
                        "error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()
                ));
    }

    public record SandboxRequest(
            String workspace,
            String path,
            String content,
            String command
    ) {
    }
}
