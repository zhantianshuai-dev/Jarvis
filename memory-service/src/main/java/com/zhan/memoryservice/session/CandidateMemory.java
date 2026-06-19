package com.zhan.memoryservice.session;

/**
 * 记忆提取的结果 — LLM 从对话中提取出一条候选记忆。
 * 对标 Python CandidateMemory。
 */
public record CandidateMemory(
    MemoryCategory category,      // 8 类之一
    String abstractText,          // L0 一句话摘要
    String overview,              // L1 结构化概览
    String content                // L2 完整叙述
) {
    public boolean supportsMerge() {
        return category == MemoryCategory.PROFILE
                || category == MemoryCategory.PREFERENCES
                || category == MemoryCategory.ENTITIES
                || category == MemoryCategory.PATTERNS;
    }
}