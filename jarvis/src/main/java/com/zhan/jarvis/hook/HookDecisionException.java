package com.zhan.jarvis.hook;

/**
 * 策略型 Hook 拒绝继续执行时抛出。
 */
public class HookDecisionException extends RuntimeException {

    public HookDecisionException(String message) {
        super(message);
    }
}
