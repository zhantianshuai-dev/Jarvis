package com.zhan.memoryservice.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 上下文类型，对标 Python ContextType(str, Enum)。
 */
public enum ContextType {
    RESOURCE("resource"),
    MEMORY("memory"),
    SKILL("skill");

    private final String value;

    ContextType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ContextType fromValue(String value) {
        for (var ct : values()) {
            if (ct.value.equalsIgnoreCase(value)) return ct;
        }
        throw new IllegalArgumentException("未知 ContextType: " + value);
    }
}
