package com.zhan.memoryservice.model;

/**
 * 检索命中的上下文片段，对标 Python MatchedContext。
 * 只包含调用方需要的信息，内部调试字段不暴露。
 */
public record MatchedContext(
    String contentId,
    ContextType contextType,
    String abstractText,      // L0 摘要，核心信息
    String overview,          // L1 概览，更详细的上下文，可为 null
    double score              // 检索分数，调用方可根据阈值过滤
) {}
