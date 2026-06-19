package com.zhan.memoryservice.model;

/**
 * 检索请求 DTO。
 */
public record SearchRequest(
    String query,
    int limit,
    String sessionId          // 可选，有 session 上下文时走 search()，否则走 find()
) {
    public SearchRequest {
        if (limit <= 0) limit = 10;
    }

    /** 纯语义检索（无 session 上下文） */
    public static SearchRequest find(String query, int limit) {
        return new SearchRequest(query, limit, null);
    }

    /** 带 session 上下文的检索 */
    public static SearchRequest search(String query, int limit, String sessionId) {
        return new SearchRequest(query, limit, sessionId);
    }
}
