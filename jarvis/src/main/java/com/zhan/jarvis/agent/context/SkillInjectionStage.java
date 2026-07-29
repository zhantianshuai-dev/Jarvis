package com.zhan.jarvis.agent.context;

import com.zhan.jarvis.skill.SkillsLoader;

import java.util.ArrayList;

public class SkillInjectionStage {

    private final SkillsLoader skillsLoader;

    public SkillInjectionStage(SkillsLoader skillsLoader) {
        this.skillsLoader = skillsLoader;
    }

    public String buildSkillsSection() {
        var parts = new ArrayList<String>();

        var alwaysSkills = skillsLoader.getAlwaysSkills();
        if (!alwaysSkills.isEmpty()) {
            String alwaysContent = skillsLoader.loadSkillsForContext(alwaysSkills);
            if (!alwaysContent.isBlank()) {
                parts.add("# Active Skills\n\n" + alwaysContent);
            }
        }

        String summary = skillsLoader.buildSkillsSummary();
        if (!summary.isBlank()) {
            parts.add("# Available Skills\n\n"
                    + "以下技能可扩展你的能力。使用 read_file 工具读取 SKILL.md 获取完整内容。\n"
                    + "这里只列出依赖已满足且未被 always 全量加载的技能。\n\n"
                    + summary);
        }

        return String.join("\n\n---\n\n", parts);
    }
}
