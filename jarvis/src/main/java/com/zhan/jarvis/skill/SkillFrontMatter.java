package com.zhan.jarvis.skill;

import java.util.List;

/**
 * 技能元数据（从 SKILL.md YAML 文件头解析）。
 */
public record SkillFrontMatter(
        String name,
        String description,
        String metadata,  // 包含 vikingbot 配置的原始 JSON 字符串
        String always,
        java.util.List<String> requiresBins,  //表示该技能依赖哪些命令行实现
        java.util.List<String> requiresEnv    //表示这个技能依赖哪些环境变量
) {
    public static final SkillFrontMatter EMPTY = new SkillFrontMatter("", "", "", "", List.of(), List.of());
}
