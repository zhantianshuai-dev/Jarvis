package com.zhan.jarvis.server.router;

import cn.hutool.core.util.IdUtil;
import com.zhan.jarvis.agent.AgentLoop;
import com.zhan.jarvis.agent.RunMode;
import com.zhan.jarvis.agent.control.ActiveRunRegistry;
import com.zhan.jarvis.agent.control.RunInterruptionService;
import com.zhan.jarvis.auth.AuthWebFilter;
import com.zhan.jarvis.channel.HttpChannel;
import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.bus.MessageBus;
import com.zhan.jarvis.concurrency.ConcurrencyController;
import com.zhan.jarvis.concurrency.SystemBusyException;
import com.zhan.jarvis.server.sse.SseEventHub;
import com.zhan.jarvis.server.sse.SseEventTypes;
import com.zhan.jarvis.session.SessionFileSpaceManager;
import com.zhan.jarvis.vision.VisionClient;
import com.zhan.jarvis.workspace.WorkspaceResolver;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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
    private final WorkspaceResolver workspaceResolver;
    private final SessionFileSpaceManager fileSpaceManager;
    private final VisionClient visionClient;
    private final ConcurrencyController concurrencyController;
    private final MessageBus messageBus;
    private final ActiveRunRegistry runRegistry;
    private final RunInterruptionService interruptionService;

    public ChatRouter(HttpChannel httpChannel, AgentLoop agentLoop, SseEventHub sseEventHub,
                      WorkspaceResolver workspaceResolver, SessionFileSpaceManager fileSpaceManager,
                      VisionClient visionClient, ConcurrencyController concurrencyController,
                      MessageBus messageBus, ActiveRunRegistry runRegistry,
                      RunInterruptionService interruptionService) {
        this.httpChannel = httpChannel;
        this.agentLoop = agentLoop;
        this.sseEventHub = sseEventHub;
        this.workspaceResolver = workspaceResolver;
        this.fileSpaceManager = fileSpaceManager;
        this.visionClient = visionClient;
        this.concurrencyController = concurrencyController;
        this.messageBus = messageBus;
        this.runRegistry = runRegistry;
        this.interruptionService = interruptionService;
    }

    @Bean
    public RouterFunction<ServerResponse> chatRoute() {
        return route(POST("/api/v1/chat"), this::handleChat)
                .andRoute(POST("/api/v1/chat/stream"), this::handleChatStream)
                .andRoute(GET("/api/v1/chat/runs/{runId}"), this::handleRunStatus)
                .andRoute(POST("/api/v1/chat/runs/{runId}/interrupt"), this::handleRunInterrupt)
                .andRoute(GET("/api/v1/workspaces"), this::handleWorkspaces)
                .andRoute(GET("/api/v1/events"), this::handleEvents)
                .andRoute(GET("/api/v1/health"), this::handleHealth);
    }

    /**
     * POST /api/v1/chat — 发送消息获取回复。
     * <p>
     * Agent 循环运行在独立虚拟线程上，不受客户端断开影响。
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
                    RunMode mode = RunMode.from(cr.mode());
                    String workspace = workspaceResolver.resolveWorkspace(cr.workspace());
                    String runId = "run_" + UUID.randomUUID();
                    log.info("收到消息: sessionId={}, mode={}, workspace={}, message={}",
                            sessionId, mode.value(), workspace, cr.message());

                    var metadata = buildMetadata(sessionId, mode, workspace, cr);
                    metadata.put("run_id", runId);
                    return new PreparedChat(runId, sessionId, userId, cr.message(), metadata);
                }).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(prepared -> {
                    runRegistry.prepare(prepared.messageId(), prepared.sessionId(), prepared.userId());
                    var future = httpChannel.submit(prepared.messageId(), prepared.sessionId(), prepared.userId(),
                            prepared.message(), prepared.metadata());
                    return Mono.fromFuture(future)
                            .timeout(RESPONSE_TIMEOUT)
                            .doOnError(ignored -> httpChannel.cancel(prepared.messageId()))
                            .doOnCancel(() -> httpChannel.cancel(prepared.messageId()))
                            .map(outbound -> Map.<String, Object>of(
                                    "session_id", outbound.sessionId(),
                                    "run_id", prepared.messageId(),
                                    "reply", outbound.content()
                            ));
                })
                .flatMap(body -> ServerResponse.ok().bodyValue(body))
                .onErrorResume(this::busyResponse); // CompletableFuture 完成后异步返回，不占用 WebFlux 工作线程
    }

    private Mono<ServerResponse> handleChatStream(ServerRequest req) {
        return req.bodyToMono(ChatRequest.class)
                .flatMap(cr -> {
                    String sessionId = cr.sessionId() != null && !cr.sessionId().isBlank()
                            ? cr.sessionId()
                            : "session_" + IdUtil.getSnowflake(1, 1).nextId();
                    String userId = authenticatedUserId(req, cr.userId());
                    RunMode mode = RunMode.from(cr.mode());
                    String workspace = workspaceResolver.resolveWorkspace(cr.workspace());
                    String runId = "run_" + UUID.randomUUID();
                    var sessionKey = new SessionKey("http", "default", sessionId);
                    log.info("收到流式消息: sessionId={}, mode={}, workspace={}, message={}",
                            sessionId, mode.value(), workspace, cr.message());

                    return Mono.fromCallable(() -> buildMetadata(sessionId, mode, workspace, cr))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(metadata -> {
                                metadata.put("run_id", runId);
                                var events = agentLoop.runStreaming(sessionKey, sessionId, cr.message(), userId,
                                                metadata)
                                        .map(this::toServerSentEvent);
                                return ServerResponse.ok()
                                        .contentType(MediaType.TEXT_EVENT_STREAM)
                                        .body(events, ServerSentEvent.class);
                            });
                });
    }

    private Mono<ServerResponse> handleRunStatus(ServerRequest req) {
        String runId = req.pathVariable("runId");
        String userId = authenticatedUserId(req, null);
        return Mono.fromCallable(() -> {
                    var snapshot = runRegistry.get(runId)
                            .orElseThrow(() -> new ActiveRunRegistry.RunNotFoundException(runId));
                    if (!snapshot.ownerUserId().isBlank() && !snapshot.ownerUserId().equals(userId)) {
                        throw new ActiveRunRegistry.RunAccessDeniedException(runId);
                    }
                    return snapshot;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(snapshot -> ServerResponse.ok().bodyValue(snapshot.toMap()))
                .onErrorResume(this::runControlErrorResponse);
    }

    private Mono<ServerResponse> handleRunInterrupt(ServerRequest req) {
        String runId = req.pathVariable("runId");
        String userId = authenticatedUserId(req, null);
        return req.bodyToMono(InterruptRequest.class)
                .defaultIfEmpty(new InterruptRequest("user_interrupted"))
                .flatMap(body -> Mono.fromCallable(() ->
                                interruptionService.interrupt(runId, userId, body.reason()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(snapshot -> ServerResponse.ok().bodyValue(snapshot.toMap()))
                .onErrorResume(this::runControlErrorResponse);
    }

    private Mono<ServerResponse> handleEvents(ServerRequest req) {
        String sessionId = req.queryParam("session_id")
                .filter(s -> !s.isBlank())
                .orElse("default");
        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(sseEventHub.subscribe(sessionId), ServerSentEvent.class);
    }

    private Mono<ServerResponse> handleWorkspaces(ServerRequest req) {
        return ServerResponse.ok().bodyValue(Map.of(
                "defaultWorkspaceId", workspaceResolver.defaultWorkspaceId(),
                "workspaces", workspaceResolver.payload()
        ));
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

    private Map<String, Object> buildMetadata(String sessionId, RunMode mode, String workspace, ChatRequest request) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("mode", mode.value());
        metadata.put("workspace", workspace);
        metadata.put("display_message", request.message() != null ? request.message() : "");
        var images = imageAttachments(request);
        if (images.isEmpty()) {
            return metadata;
        }
        if (!visionClient.enabled()) {
            metadata.put("vision_disabled", true);
            return metadata;
        }
        metadata.put("vision_image_urls", imageDataUris(sessionId, images));
        metadata.put("attachment_count", images.size());
        return metadata;
    }

    private List<ChatAttachment> imageAttachments(ChatRequest request) {
        List<ChatAttachment> attachments = request.attachments() != null ? request.attachments() : List.of();
        var images = new ArrayList<ChatAttachment>();
        for (ChatAttachment attachment : attachments) {
            if (attachment != null && isImage(attachment)) {
                images.add(attachment);
            }
        }
        return images;
    }

    private List<String> imageDataUris(String sessionId, List<ChatAttachment> attachments) {
        var urls = new ArrayList<String>();
        for (ChatAttachment attachment : attachments) {
            try {
                Path path = trustedUploadPath(sessionId, attachment);
                byte[] bytes = Files.readAllBytes(path);
                String mediaType = attachment.contentType() != null && !attachment.contentType().isBlank()
                        ? attachment.contentType()
                        : "image/png";
                urls.add("data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes));
            } catch (Exception e) {
                throw new IllegalArgumentException("读取图片附件失败: " + e.getMessage(), e);
            }
        }
        return urls;
    }

    private Path trustedUploadPath(String sessionId, ChatAttachment attachment) {
        var uploads = fileSpaceManager.ensure(sessionId).uploads().toAbsolutePath().normalize();
        Path path = Path.of(attachment.path() != null ? attachment.path() : "").toAbsolutePath().normalize();
        if (!path.startsWith(uploads)) {
            throw new IllegalArgumentException("附件不属于当前会话 uploads 目录");
        }
        return path;
    }

    private boolean isImage(ChatAttachment attachment) {
        String contentType = attachment.contentType();
        return contentType != null && contentType.startsWith("image/");
    }

    /**
     * GET /api/v1/health — 健康检查。
     */
    private Mono<ServerResponse> handleHealth(ServerRequest req) {
        return ServerResponse.ok().bodyValue(Map.of(
                "status", "ok",
                "service", "Jarvis",
                "concurrency", concurrencyController.snapshot(),
                "message_bus", Map.of(
                        "queued", messageBus.queuedCount(),
                        "pending", messageBus.pendingCount()
                )
        ));
    }

    private Mono<ServerResponse> busyResponse(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SystemBusyException) {
                return ServerResponse.status(429).bodyValue(Map.of(
                        "error", "system_busy",
                        "message", current.getMessage()
                ));
            }
            current = current.getCause();
        }
        return Mono.error(error);
    }

    private Mono<ServerResponse> runControlErrorResponse(Throwable error) {
        if (error instanceof ActiveRunRegistry.RunAccessDeniedException) {
            return ServerResponse.status(403).bodyValue(Map.of(
                    "error", "run_access_denied",
                    "message", error.getMessage()
            ));
        }
        if (error instanceof ActiveRunRegistry.RunNotFoundException) {
            return ServerResponse.notFound().build();
        }
        return Mono.error(error);
    }

    private record PreparedChat(String messageId, String sessionId, String userId,
                                String message, Map<String, Object> metadata) {}

    private record InterruptRequest(String reason) {}
}
