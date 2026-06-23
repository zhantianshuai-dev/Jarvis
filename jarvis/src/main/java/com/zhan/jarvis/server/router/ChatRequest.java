package com.zhan.jarvis.server.router;

/**
 * Chat API 请求体。
 */
public record ChatRequest(
    String sessionId,
    String message,
    String userId,
    String mode,
    String workspace
) {}
