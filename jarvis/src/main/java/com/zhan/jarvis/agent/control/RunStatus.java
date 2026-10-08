package com.zhan.jarvis.agent.control;

/**
 * 单次用户请求的生命周期状态。
 */
public enum RunStatus {
    QUEUED,
    RUNNING,
    WAITING_CONFIRMATION,
    CANCELLING,
    COMPLETED,
    INTERRUPTED,
    FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == INTERRUPTED || this == FAILED;
    }

    public String value() {
        return name().toLowerCase();
    }
}
