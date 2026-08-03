package com.zhan.jarvis.agent.runtime;

import com.zhan.jarvis.agent.RunMode;
import com.zhan.jarvis.agent.loop.TokenUsageAccumulator;
import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.llm.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 单次运行的统一状态。
 * 这个对象对齐 deer-flow 的 ThreadState 思路，集中承载消息、工具暴露、
 * 上传文件、输出产物、沙箱和 token 等横切状态。
 */
public record RunState(
        String runId,
        SessionKey sessionKey,
        String sessionId,
        String userId,
        RunMode runMode,
        String workspace,
        Map<String, Object> metadata,
        Map<String, Object> outputMetadata,
        List<Message> messages,
        int startIteration,
        boolean stream,
        TokenUsageAccumulator tokenUsage,
        Set<String> activeDeferredTools,
        List<RuntimeFileRef> uploadedFiles,
        List<RuntimeArtifact> artifacts,
        SandboxRuntimeState sandbox
) {
    public RunState {
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
        outputMetadata = outputMetadata == null ? Map.of() : new LinkedHashMap<>(outputMetadata);
        messages = messages == null ? new ArrayList<>() : messages;
        tokenUsage = tokenUsage == null ? new TokenUsageAccumulator() : tokenUsage;
        activeDeferredTools = activeDeferredTools == null ? new LinkedHashSet<>() : activeDeferredTools;
        uploadedFiles = uploadedFiles == null ? new ArrayList<>() : uploadedFiles;
        artifacts = artifacts == null ? new ArrayList<>() : artifacts;
        sandbox = sandbox == null ? SandboxRuntimeState.empty(workspace) : sandbox;
    }

    public static RunState create(String runId, SessionKey sessionKey, String sessionId, String userId,
                                  Map<String, Object> metadata, List<Message> messages,
                                  int startIteration, boolean stream,
                                  Map<String, Object> outputMetadata,
                                  TokenUsageAccumulator tokenUsage,
                                  RunMode runMode, Set<String> activeDeferredTools) {
        String workspace = stringValue(metadata == null ? null : metadata.get("workspace"));
        return new RunState(
                runId,
                sessionKey,
                sessionId,
                userId,
                runMode,
                workspace,
                metadata,
                outputMetadata,
                messages,
                startIteration,
                stream,
                tokenUsage,
                activeDeferredTools,
                new ArrayList<>(),
                new ArrayList<>(),
                SandboxRuntimeState.empty(workspace)
        );
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
