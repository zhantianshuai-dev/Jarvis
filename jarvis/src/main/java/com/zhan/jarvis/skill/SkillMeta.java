package com.zhan.jarvis.skill;

import java.util.List;

/**
 * Jarvis 技能元数据（从 YAML 文件头的 metadata JSON 中解析 "jarvis" 字段）。
 * <pre>
 * metadata: {"jarvis":{"emoji":"*","requires":{"bins":["tmux"]},"always":true}}
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
