package com.zhan.jarvis.server.router;

import cn.hutool.core.util.IdUtil;
import com.zhan.jarvis.artifact.ArtifactManager;
import com.zhan.jarvis.auth.AuthWebFilter;
import com.zhan.jarvis.session.SessionFileSpaceManager;
import com.zhan.jarvis.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * 聊天附件上传接口。
 * 第一版仅支持图片，保存到当前会话的 uploads 目录，供视觉模型读取。
 */
@Configuration
public class ChatAttachmentRouter {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentRouter.class);
    private static final long MAX_IMAGE_BYTES = 7L * 1024 * 1024;

    private final SessionManager sessionManager;
    private final SessionFileSpaceManager fileSpaceManager;
    private final ArtifactManager artifactManager;

    public ChatAttachmentRouter(SessionManager sessionManager, SessionFileSpaceManager fileSpaceManager,
                                ArtifactManager artifactManager) {
        this.sessionManager = sessionManager;
        this.fileSpaceManager = fileSpaceManager;
        this.artifactManager = artifactManager;
    }

    @Bean
    public RouterFunction<ServerResponse> chatAttachmentRoute() {
        return route(POST("/api/v1/chat/sessions/{sessionId}/attachments"), this::handleUpload);
    }

    private Mono<ServerResponse> handleUpload(ServerRequest req) {
        String userId = currentUserId(req);
        String sessionId = req.pathVariable("sessionId");
        return req.multipartData()
                .flatMap(parts -> {
                    var part = parts.toSingleValueMap().get("file");
                    if (!(part instanceof FilePart filePart)) {
                        return ServerResponse.badRequest()
                                .bodyValue(Map.of("success", false, "msg", "缺少 file 文件字段"));
                    }
                    String contentType = filePart.headers().getContentType() != null
                            ? filePart.headers().getContentType().toString()
                            : "";
                    if (!contentType.startsWith("image/")) {
                        return ServerResponse.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                                .bodyValue(Map.of("success", false, "msg", "仅支持图片附件"));
                    }
                    return Mono.fromCallable(() -> prepareTarget(sessionId, userId, filePart.filename(), contentType))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(prepared -> filePart.transferTo(prepared.path())
                                    .then(Mono.fromCallable(() -> finishUpload(sessionId, userId, prepared))
                                            .subscribeOn(Schedulers.boundedElastic())))
                            .flatMap(attachment -> ServerResponse.ok().bodyValue(Map.of(
                                    "success", true,
                                    "attachment", attachment
                            )))
                            .onErrorResume(SecurityException.class, e ->
                                    ServerResponse.status(HttpStatus.FORBIDDEN)
                                            .bodyValue(Map.of("success", false, "msg", e.getMessage())))
                            .onErrorResume(IllegalArgumentException.class, e ->
                                    ServerResponse.badRequest()
                                            .bodyValue(Map.of("success", false, "msg", e.getMessage())));
                });
    }

    private PreparedUpload prepareTarget(String sessionId, String userId, String filename, String contentType) throws Exception {
        var messages = sessionManager.getSessionMessages(sessionId, 1);
        if (messages.ownerUserId() != null && !messages.ownerUserId().isBlank()
                && !messages.ownerUserId().equals(userId)) {
            throw new SecurityException("无权上传到该会话");
        }
        var space = fileSpaceManager.ensure(sessionId);
        String id = "att_" + IdUtil.fastSimpleUUID();
        String safeName = sanitizeFilename(filename);
        String ext = extensionOf(safeName, contentType);
        Path target = space.uploads().resolve(id + ext).normalize();
        if (!target.startsWith(space.uploads())) {
            throw new IllegalArgumentException("非法附件路径");
        }
        return new PreparedUpload(id, safeName, contentType, target);
    }

    private ChatAttachment finishUpload(String sessionId, String userId, PreparedUpload prepared) throws Exception {
        long size = Files.size(prepared.path());
        if (size <= 0 || size > MAX_IMAGE_BYTES) {
            Files.deleteIfExists(prepared.path());
            throw new IllegalArgumentException("图片大小必须在 1B 到 7MB 之间");
        }
        var attachment = new ChatAttachment(
                prepared.id(),
                prepared.name(),
                prepared.contentType(),
                size,
                prepared.path().toString()
        );
        artifactManager.registerUpload(sessionId, userId, prepared.name(), prepared.path().toString(),
                prepared.contentType(), size, "用户上传图片附件", Map.of(
                        "attachment_id", prepared.id(),
                        "purpose", "chat_image"
                ));
        log.info("聊天图片附件已保存: id={}, file={}, size={}", attachment.id(), attachment.path(), size);
        return attachment;
    }

    private String sanitizeFilename(String filename) {
        String name = filename == null || filename.isBlank() ? "image" : filename;
        name = Path.of(name).getFileName().toString();
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isBlank() ? "image" : name;
    }

    private String extensionOf(String filename, String contentType) {
        String lower = filename.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && dot < lower.length() - 1 && lower.length() - dot <= 6) {
            return lower.substring(dot);
        }
        return switch (contentType) {
            case MediaType.IMAGE_JPEG_VALUE -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".png";
        };
    }

    private String currentUserId(ServerRequest req) {
        Object userId = req.exchange().getAttribute(AuthWebFilter.ATTR_USER_ID);
        if (userId != null && !String.valueOf(userId).isBlank()) {
            return String.valueOf(userId);
        }
        Object username = req.exchange().getAttribute(AuthWebFilter.ATTR_USERNAME);
        return username != null ? String.valueOf(username) : "anonymous";
    }

    private record PreparedUpload(String id, String name, String contentType, Path path) {}
}
