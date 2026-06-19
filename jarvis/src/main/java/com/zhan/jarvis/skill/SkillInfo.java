package com.zhan.jarvis.skill;

/**
 * 技能简介 — 用于列表和摘要展示。
 */
public record SkillInfo(
        String name,
        String path,
        String description,
        boolean available
) {}