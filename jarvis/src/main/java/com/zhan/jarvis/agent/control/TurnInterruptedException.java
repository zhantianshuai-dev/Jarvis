package com.zhan.jarvis.agent.control;

/**
 * 用户或父任务主动中断当前 Run，不属于普通业务失败。
 */
public class TurnInterruptedException extends RuntimeException {

    public TurnInterruptedException(String reason) {
        super(reason == null || reason.isBlank() ? "Run 已被中断" : reason);
    }

    public TurnInterruptedException(String reason, Throwable cause) {
        super(reason == null || reason.isBlank() ? "Run 已被中断" : reason, cause);
    }
}
