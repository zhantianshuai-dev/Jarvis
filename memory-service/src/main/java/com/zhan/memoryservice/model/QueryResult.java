package com.zhan.memoryservice.model;

import java.util.List;

/**
 * 单个 TypedQuery 的检索结果，对标 Python QueryResult。
 */
public record QueryResult(
    TypedQuery query,                      // 原始查询
    List<MatchedContext> matchedContexts,  // 匹配结果
    List<String> searchedDirectories      // 被搜索过的目录
) {}
