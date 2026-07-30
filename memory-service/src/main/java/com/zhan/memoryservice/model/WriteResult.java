package com.zhan.memoryservice.model;

/**
 * 写入结果 DTO。
 */
public record WriteResult(
    String contentId,
    String semanticStatus,   // "queued" | "complete" | "failed"
    String vectorStatus      // "queued" | "complete" | "failed"
) {}
