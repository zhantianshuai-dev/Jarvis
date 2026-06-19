package com.zhan.jarvis.skill;

import java.util.List;

/**
 * 技能元数据（从 SKILL.md YAML frontmatter 解析）。
 */
public record SkillFrontMatter(
        String name,
        String description,
        String metadata,  // raw JSON string containing vikingbot config
        String always,
        java.util.List<String> requiresBins,  //表示该技能依赖哪些命令行实现
        java.util.List<String> requiresEnv    //表示这个技能依赖哪些环境变量
) {
    public static final SkillFrontMatter EMPTY = new SkillFrontMatter("", "", "", "", List.of(), List.of());
}
