package com.zhan.jarvis.server.router;

import com.zhan.jarvis.auth.AuthWebFilter;
import com.zhan.jarvis.git.WorktreeManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * Worktree 管理接口。
 * 这些接口只给后端/前端管理使用，不注册为 LLM 可见工具。
 */
@Configuration
public class GitWorktreeRouter {

    private static final String ADMIN_USERNAME = "admin";

    private final WorktreeManager worktreeManager;

    public GitWorktreeRouter(WorktreeManager worktreeManager) {
        this.worktreeManager = worktreeManager;
    }

    @Bean
    public RouterFunction<ServerResponse> gitWorktreeRoute() {
        return route(GET("/api/v1/git/worktrees"), this::handleList)  //查看所有 worktree
                .andRoute(POST("/api/v1/git/worktrees"), this::handleCreate) // 创建 worktree，仅 admin
                .andRoute(GET("/api/v1/git/worktrees/{name}"), this::handleDetail) //查看单个 worktree 详情
                .andRoute(POST("/api/v1/git/worktrees/{name}/keep"), this::handleKeep) //标记保留，仅 admin
                .andRoute(DELETE("/api/v1/git/worktrees/{name}"), this::handleDelete); // 删除 worktree，仅 admin
    }

    private Mono<ServerResponse> handleList(ServerRequest req) {
        return Mono.fromCallable(() -> worktreeManager.list().stream()
                        .map(worktreeManager::entryPayload)
                        .toList())
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(items -> ServerResponse.ok().bodyValue(Map.of(
                        "success", true,
                        "worktrees", items
                )))
                .onErrorResume(this::errorResponse);
    }

    private Mono<ServerResponse> handleCreate(ServerRequest req) {
        if (!isAdmin(req)) {
            return forbidden("只有 admin 可以创建 worktree");
        }
        return req.bodyToMono(Map.class)
                .defaultIfEmpty(Map.of())
                .flatMap(body -> Mono.fromCallable(() -> {
                            String name = value(body, "name");
                            if (name.isBlank()) {
                                throw new IllegalArgumentException("name 不能为空");
                            }
                            String baseRef = firstValue(body, "base_ref", "baseRef");
                            String taskId = firstValue(body, "task_id", "taskId");
                            return worktreeManager.create(name, baseRef, taskId);
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(this::resultResponse)
                .onErrorResume(this::errorResponse);
    }

    private Mono<ServerResponse> handleDetail(ServerRequest req) {
        String name = req.pathVariable("name");
        return Mono.fromCallable(() -> worktreeManager.get(name))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(entry -> entry
                        .map(worktree -> ServerResponse.ok().bodyValue(Map.of(
                                "success", true,
                                "worktree", worktreeManager.entryPayload(worktree)
                        )))
                        .orElseGet(() -> ServerResponse.status(HttpStatus.NOT_FOUND).bodyValue(Map.of(
                                "success", false,
                                "msg", "worktree 不存在: " + name
                        ))))
                .onErrorResume(this::errorResponse);
    }

    private Mono<ServerResponse> handleKeep(ServerRequest req) {
        if (!isAdmin(req)) {
            return forbidden("只有 admin 可以标记保留 worktree");
        }
        String name = req.pathVariable("name");
        return Mono.fromCallable(() -> worktreeManager.keep(name))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(this::resultResponse)
                .onErrorResume(this::errorResponse);
    }

    private Mono<ServerResponse> handleDelete(ServerRequest req) {
        if (!isAdmin(req)) {
            return forbidden("只有 admin 可以删除 worktree");
        }
        String name = req.pathVariable("name");
        boolean force = req.queryParam("force")
                .map(Boolean::parseBoolean)
                .orElse(false);
        if (force) {
            return ServerResponse.status(HttpStatus.BAD_REQUEST).bodyValue(Map.of(
                    "success", false,
                    "msg", "当前版本禁止 force 删除 worktree，后续接入确认机制后再开放"
            ));
        }
        return Mono.fromCallable(() -> worktreeManager.remove(name, false))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(this::resultResponse)
                .onErrorResume(this::errorResponse);
    }

    private Mono<ServerResponse> resultResponse(WorktreeManager.WorktreeResult result) {
        if (!result.success()) {
            var body = new LinkedHashMap<String, Object>();
            body.put("success", false);
            body.put("action", result.action());
            body.put("msg", result.error());
            body.put("command", commandPayload(result.command()));
            return ServerResponse.status(HttpStatus.BAD_REQUEST).bodyValue(body);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("success", true);
        body.put("action", result.action());
        body.put("worktree", result.data());
        body.put("command", commandPayload(result.command()));
        return ServerResponse.ok().bodyValue(body);
    }

    private Map<String, Object> commandPayload(WorktreeManager.GitProcessResult command) {
        if (command == null) {
            return Map.of();
        }
        return Map.of(
                "exit_code", command.exitCode(),
                "timed_out", command.timedOut(),
                "output", command.output()
        );
    }

    private Mono<ServerResponse> errorResponse(Throwable error) {
        if (error instanceof IllegalArgumentException || error instanceof IOException) {
            return ServerResponse.status(HttpStatus.BAD_REQUEST).bodyValue(Map.of(
                    "success", false,
                    "msg", error.getMessage()
            ));
        }
        return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).bodyValue(Map.of(
                "success", false,
                "msg", error.getMessage()
        ));
    }

    private Mono<ServerResponse> forbidden(String message) {
        return ServerResponse.status(HttpStatus.FORBIDDEN).bodyValue(Map.of(
                "success", false,
                "msg", message
        ));
    }

    private boolean isAdmin(ServerRequest req) {
        Object username = req.exchange().getAttribute(AuthWebFilter.ATTR_USERNAME);
        return ADMIN_USERNAME.equals(String.valueOf(username));
    }

    private String firstValue(Map<?, ?> body, String... keys) {
        return List.of(keys).stream()
                .map(key -> value(body, key))
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private String value(Map<?, ?> body, String key) {
        if (body == null) {
            return "";
        }
        Object value = body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
