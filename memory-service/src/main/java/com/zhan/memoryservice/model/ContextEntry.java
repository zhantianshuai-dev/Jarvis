package com.zhan.memoryservice.model;

import java.time.Instant;
import java.util.List;

/**
 * 一个上下文条目，对标 Python Context。
 * 代表一个存储在系统中的知识单元，可以是文档、记忆或技能。
 *
 * level 约定：
 *   0 = L0 摘要（~100 tokens）
 *   1 = L1 概览（~2k tokens）
 *   2 = L2 完整内容
 */
public record ContextEntry(
    String contentId,
    String content,          // L2 完整内容
    String abstractText,     // L0 一句话摘要
    String overview,         // L1 概览
    int level,
    ContextType contextType,
    String parentId,         // 父条目 ID，null 表示根
    int activeCount,         // 被检索/访问次数
    Instant createdAt,
    Instant updatedAt,
    List<String> relations   // 关联条目的 contentId 列表
) {
    /** 创建新条目时的便捷工厂方法 */
    public static ContextEntry createNew(
        String contentId, String content, ContextType contextType, String parentId
    ) {
        return new ContextEntry(
            contentId, content, "", "", 2,
            contextType, parentId, 0,
            Instant.now(), Instant.now(), List.of()
        );
    }

    /** 更新 abstract 和 overview（异步摘要生成完成后调用） */
    public ContextEntry withSummaries(String abstractText, String overview) {
        return new ContextEntry(
            contentId, content, abstractText, overview, level,
            contextType, parentId, activeCount, createdAt, Instant.now(), relations
        );
    }
}
