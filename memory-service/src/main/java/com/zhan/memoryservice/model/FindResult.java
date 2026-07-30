package com.zhan.memoryservice.model;

import java.util.List;

/**
 * 最终检索结果，FindResult。
 * 按记忆、资源、技能分桶返回，只包含调用方需要的信息。
 * 查询计划和查询结果等内部调试信息通过日志输出，不在此暴露。
 */
public record FindResult(
    List<MatchedContext> memories,
    List<MatchedContext> resources,
    List<MatchedContext> skills
) {
    /** 空结果 */
    public static FindResult empty() {
        return new FindResult(List.of(), List.of(), List.of());
    }
}
