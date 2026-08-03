package com.zhan.jarvis.agent.loop;

import com.zhan.jarvis.agent.RunMode;
import com.zhan.jarvis.agent.runtime.RunState;
import com.zhan.jarvis.agent.runtime.RuntimeArtifact;
import com.zhan.jarvis.agent.runtime.RuntimeFileRef;
import com.zhan.jarvis.agent.runtime.SandboxRuntimeState;
import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.llm.Message;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AgentLoop 内部状态视图。
 * 现在它包装统一的 RunState，保留原访问方法，便于逐步迁移到 Runtime/Middleware 架构。
 */
public record LoopState(RunState runtime) {

    public LoopState(String runId, SessionKey sessionKey, String sessionId, String userId,
                     Map<String, Object> metadata, List<Message> messages,
                     int startIteration, boolean stream,
                     Map<String, Object> outputMetadata,
                     TokenUsageAccumulator tokenUsage,
                     RunMode runMode,
                     Set<String> activeDeferredTools) {
        this(RunState.create(runId, sessionKey, sessionId, userId, metadata, messages, startIteration,
                stream, outputMetadata, tokenUsage, runMode, activeDeferredTools));
    }

    public String runId() {
        return runtime.runId();
    }

    public SessionKey sessionKey() {
        return runtime.sessionKey();
    }

    public String sessionId() {
        return runtime.sessionId();
    }

    public String userId() {
        return runtime.userId();
    }

    public Map<String, Object> metadata() {
        return runtime.metadata();
    }

    public List<Message> messages() {
        return runtime.messages();
    }

    public int startIteration() {
        return runtime.startIteration();
    }

    public boolean stream() {
        return runtime.stream();
    }

    public Map<String, Object> outputMetadata() {
        return runtime.outputMetadata();
    }

    public TokenUsageAccumulator tokenUsage() {
        return runtime.tokenUsage();
    }

    public RunMode runMode() {
        return runtime.runMode();
    }

    public Set<String> activeDeferredTools() {
        return runtime.activeDeferredTools();
    }

    public String workspace() {
        return runtime.workspace();
    }

    public List<RuntimeFileRef> uploadedFiles() {
        return runtime.uploadedFiles();
    }

    public List<RuntimeArtifact> artifacts() {
        return runtime.artifacts();
    }

    public SandboxRuntimeState sandbox() {
        return runtime.sandbox();
    }
}
