package com.zhan.jarvis.sandbox;

/**
 * 命令执行结果。
 */
public record CommandResult(
    int exitCode,
    String output,
    boolean timedOut,
    long timeoutSeconds
) {
    public String format() {
        if (timedOut) {
            return output + "\n[命令超时 (" + timeoutSeconds + "s)，已强制终止]";
        }
        String prefix = exitCode == 0
                ? "命令执行成功 (exit=0):\n"
                : "命令执行失败 (exit=" + exitCode + "):\n";
        return prefix + output;
    }
}
