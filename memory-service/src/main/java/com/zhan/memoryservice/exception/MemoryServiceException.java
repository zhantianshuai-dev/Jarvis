package com.zhan.memoryservice.exception;

import org.springframework.http.HttpStatus;

/**
 * 记忆服务统一异常基类。
 * 所有业务异常继承此类，GlobalExceptionHandler 根据 status 字段映射 HTTP 状态码。
 */
public class MemoryServiceException extends RuntimeException {

    private final HttpStatus status;

    public MemoryServiceException(String message) {
        super(message);
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
    }

    public MemoryServiceException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public MemoryServiceException(String message, Throwable cause) {
        super(message, cause);
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
    }

    public MemoryServiceException(String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    // ---- 预定义子类 ----

    /** LLM 调用失败（网络、API key、模型错误等） */
    public static class LLMException extends MemoryServiceException {
        public LLMException(String message) { super(message, HttpStatus.BAD_GATEWAY); }
        public LLMException(String message, Throwable cause) { super(message, HttpStatus.BAD_GATEWAY, cause); }
    }

    /** Embedding 调用失败 */
    public static class EmbeddingException extends MemoryServiceException {
        public EmbeddingException(String message) { super(message, HttpStatus.BAD_GATEWAY); }
        public EmbeddingException(String message, Throwable cause) { super(message, HttpStatus.BAD_GATEWAY, cause); }
    }

    /** 请求参数校验失败 */
    public static class ValidationException extends MemoryServiceException {
        public ValidationException(String message) { super(message, HttpStatus.BAD_REQUEST); }
    }

    /** 资源不存在 */
    public static class NotFoundException extends MemoryServiceException {
        public NotFoundException(String message) { super(message, HttpStatus.NOT_FOUND); }
    }

    /** 存储层错误（数据库、Milvus 等） */
    public static class StorageException extends MemoryServiceException {
        public StorageException(String message) { super(message, HttpStatus.INTERNAL_SERVER_ERROR); }
        public StorageException(String message, Throwable cause) { super(message, HttpStatus.INTERNAL_SERVER_ERROR, cause); }
    }
}
