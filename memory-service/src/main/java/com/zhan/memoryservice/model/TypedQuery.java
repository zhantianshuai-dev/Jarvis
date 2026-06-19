package com.zhan.memoryservice.model;

import java.util.List;

/**
 * 带类型的查询，对标 Python TypedQuery。
 * IntentAnalyzer 输出的一个子查询，指定目标 context_type 和优先级。
 */
public record TypedQuery(
    String query,                  // 查询文本
    ContextType contextType,       // 目标类型，null 表示不限
    String intent,                 // 查询意图描述
    int priority,                  // 优先级 1-5，1 最高
    List<String> targetDirectories // 限定检索目录，空列表表示不限
) {}
