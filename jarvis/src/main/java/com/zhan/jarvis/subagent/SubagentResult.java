package com.zhan.jarvis.subagent;

/**
 * 子 Agent 执行结果。
 *
 * @param taskId  任务 ID
 * @param status  状态: running / completed / failed / cancelled / timed_out
 * @param result  结果文本（完成时）
 * @param error   错误信息（失败时）
 */
public record SubagentResult(
    String taskId,
    String status,
    String result,
    String error
) {
    public static SubagentResult completed(String taskId, String result) {
        return new SubagentResult(taskId, "completed", result, null);
    }

    public static SubagentResult failed(String taskId, String error) {
        return new SubagentResult(taskId, "failed", null, error);
    }

    public static SubagentResult running(String taskId) {
        return new SubagentResult(taskId, "running", null, null);
    }

    public static SubagentResult cancelled(String taskId, String reason) {
        return new SubagentResult(taskId, "cancelled", null, reason);
    }

    public static SubagentResult timedOut(String taskId, String reason) {
        return new SubagentResult(taskId, "timed_out", null, reason);
    }
}
