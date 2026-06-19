package com.zhan.memoryservice.model;

/**
 * 写入结果 DTO，对标 Python ContentWriteCoordinator 的返回值。
 */
public record WriteResult(
    String contentId,
    String semanticStatus,   // "queued" | "complete" | "failed"
    String vectorStatus      // "queued" | "complete" | "failed"
) {}
