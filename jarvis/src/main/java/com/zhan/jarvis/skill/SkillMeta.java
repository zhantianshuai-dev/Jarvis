package com.zhan.jarvis.skill;

import java.util.List;

/**
 * vikingbot 技能元数据（从 frontmatter.metadata JSON 的 "vikingbot" 字段解析）。
 * <pre>
 * metadata: {"vikingbot":{"emoji":"🧵","requires":{"bins":["tmux"]},"always":true}}
 * </pre>
 */
public record SkillMeta(
        String emoji,
        List<String> os,
        boolean always,
        Requires requires
) {
    public record Requires(List<String> bins, List<String> env) {}

    public static final SkillMeta EMPTY = new SkillMeta("", List.of(), false, new Requires(List.of(), List.of()));
}