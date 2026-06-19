package com.zhan.jarvis.task;

public record TaskRecord(
        String taskId,
        String type,
        String status,
        String ownerUserId,
        String sessionId,
        String sessionKey,
        String description,
        String worktree,
        String worktreePath,
        String result,
        String error,
        String createdAt,
        String updatedAt
) {
    TaskRecord withStatus(String status, String result, String error, String updatedAt) {
        return new TaskRecord(taskId, type, status, ownerUserId, sessionId, sessionKey, description,
                worktree, worktreePath, result, error, createdAt, updatedAt);
    }

    TaskRecord withWorktree(String worktree, String worktreePath, String updatedAt) {
        return new TaskRecord(taskId, type, status, ownerUserId, sessionId, sessionKey, description,
                worktree, worktreePath, result, error, createdAt, updatedAt);
    }
}
