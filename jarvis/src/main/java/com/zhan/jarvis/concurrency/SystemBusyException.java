package com.zhan.jarvis.concurrency;

/**
 * 系统并发配额或有界队列已满。
 */
public class SystemBusyException extends RuntimeException {

    public SystemBusyException(String message) {
        super(message);
    }

    public SystemBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
