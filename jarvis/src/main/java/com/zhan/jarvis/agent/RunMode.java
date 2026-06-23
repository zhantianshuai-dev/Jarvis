package com.zhan.jarvis.agent;

/**
 * 前端显式选择的运行模式。
 */
public enum RunMode {
    CHAT("chat"),
    AGENT("agent"),
    SUPER_AGENT("super_agent");

    private final String value;

    RunMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public int maxIterations(int configuredMax) {
        return switch (this) {
            case CHAT -> 3;
            case AGENT -> configuredMax;
            case SUPER_AGENT -> Math.max(configuredMax, 30);
        };
    }

    public static RunMode from(Object raw) {
        if (raw == null) {
            return AGENT;
        }
        String text = String.valueOf(raw).strip().toLowerCase().replace("-", "_");
        return switch (text) {
            case "chat", "/chat" -> CHAT;
            case "super_agent", "superagent", "/super_agent", "/super-agent" -> SUPER_AGENT;
            case "agent", "/agent", "" -> AGENT;
            default -> AGENT;
        };
    }
}
