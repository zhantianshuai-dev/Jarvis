package com.zhan.memoryservice.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 8 类记忆，对标 Python MemoryCategory。
 */
public enum MemoryCategory {
    PROFILE("profile"),           // 用户画像（始终合并）
    PREFERENCES("preferences"),   // 偏好（支持合并）
    ENTITIES("entities"),         // 实体/概念（支持合并）
    EVENTS("events"),             // 事件（独立不合并）
    CASES("cases"),               // 案例（Agent 经验，独立不合并）
    PATTERNS("patterns"),         // 模式/洞察（支持合并）
    TOOLS("tools"),               // 工具使用经验（特殊统计合并）
    SKILLS("skills");             // 技能使用经验（特殊统计合并）

    private final String value;

    MemoryCategory(String value) { this.value = value; }

    @JsonValue public String value() { return value; }

    @JsonCreator
    public static MemoryCategory fromValue(String v) {
        for (var c : values()) if (c.value.equalsIgnoreCase(v)) return c;
        throw new IllegalArgumentException("未知 MemoryCategory: " + v);
    }

    /** profile/preferences/entities 属于 User，其余属于 Agent */
    public boolean isUserMemory() {
        return this == PROFILE || this == PREFERENCES || this == ENTITIES || this == EVENTS;
    }
}