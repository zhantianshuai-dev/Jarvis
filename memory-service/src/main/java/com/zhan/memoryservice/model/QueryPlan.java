package com.zhan.memoryservice.model;

import java.util.List;

/**
 * 查询计划，对标 Python QueryPlan。
 * IntentAnalyzer 的输出，包含多个 TypedQuery 和 LLM 的推理过程。
 */
public record QueryPlan(
    List<TypedQuery> queries,      // 子查询列表
    String sessionContext,         // 会话上下文摘要
    String reasoning               // LLM 推理过程
) {}
