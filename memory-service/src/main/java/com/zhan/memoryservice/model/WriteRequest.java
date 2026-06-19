package com.zhan.memoryservice.model;

/**
 * 写入请求 DTO。
 */
public record WriteRequest(
    String contentId,
    String content,
    ContextType contextType,
    String parentId       // 可选，父条目 ID
) {}
