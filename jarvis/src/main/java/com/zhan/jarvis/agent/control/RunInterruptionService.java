package com.zhan.jarvis.agent.control;

import com.zhan.jarvis.bus.MessageBus;
import com.zhan.jarvis.permission.AgentCheckpointStore;
import com.zhan.jarvis.permission.ToolPermissionManager;
import com.zhan.jarvis.subagent.SubagentManager;

/**
 * Run 终止编排服务：停止活动执行，并撤销仍可恢复执行的人工确认凭证。
 */
public class RunInterruptionService {

    private final ActiveRunRegistry runRegistry;
    private final MessageBus messageBus;
    private final AgentCheckpointStore checkpointStore;
    private final ToolPermissionManager permissionManager;
    private final SubagentManager subagentManager;

    public RunInterruptionService(ActiveRunRegistry runRegistry, MessageBus messageBus,
                                  AgentCheckpointStore checkpointStore,
                                  ToolPermissionManager permissionManager,
                                  SubagentManager subagentManager) {
        this.runRegistry = runRegistry;
        this.messageBus = messageBus;
        this.checkpointStore = checkpointStore;
        this.permissionManager = permissionManager;
        this.subagentManager = subagentManager;
    }

    public RunSnapshot interrupt(String runId, String requesterUserId, String reason) {
        RunSnapshot before = runRegistry.get(runId)
                .orElseThrow(() -> new ActiveRunRegistry.RunNotFoundException(runId));
        RunSnapshot snapshot = runRegistry.interrupt(runId, requesterUserId, reason);
        boolean removedFromQueue = messageBus.cancel(runId);
        checkpointStore.revokeRun(runId);
        permissionManager.revokeRun(runId);
        subagentManager.cancelByParentRun(runId, reason);
        if (before.status() == RunStatus.WAITING_CONFIRMATION
                || (before.status() == RunStatus.QUEUED && removedFromQueue)) {
            snapshot = runRegistry.interrupted(runId,
                    reason == null || reason.isBlank() ? "user_interrupted" : reason);
        }
        return snapshot;
    }
}
