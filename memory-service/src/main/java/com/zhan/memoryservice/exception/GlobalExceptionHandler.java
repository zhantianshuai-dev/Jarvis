package com.zhan.memoryservice.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * 全局异常处理器 — 将异常映射为统一格式的 JSON 响应，同时输出完整堆栈供排查。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MemoryServiceException.class)
    public Mono<ResponseEntity<ErrorBody>> handleMemoryService(MemoryServiceException ex) {
        log.error("请求处理异常: {}", ex.toString(), ex);
        var body = new ErrorBody(ex.status().value(), ex.getClass().getSimpleName(), ex.getMessage(), Instant.now());
        return Mono.just(ResponseEntity.status(ex.status()).body(body));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ErrorBody>> handleUnknown(Exception ex) {
        log.error("未预期异常", ex);
        var body = new ErrorBody(500, "InternalError", ex.getMessage(), Instant.now());
        return Mono.just(ResponseEntity.status(500).body(body));
    }

    /** 统一错误响应体 */
    public record ErrorBody(int status, String error, String message, Instant timestamp) {}
}
