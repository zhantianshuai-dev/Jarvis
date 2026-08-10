package com.zhan.jarvis.agent.context;

import com.zhan.jarvis.llm.Message;
import com.zhan.jarvis.memory.MemoryServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 在会话首轮创建一次记忆快照。
 * 后续请求从会话上下文复用该快照，避免像普通检索那样每轮执行向量查询并重复注入结果。
 */
public class SessionMemorySnapshotStage implements ContextStage {

    private static final Logger log = LoggerFactory.getLogger(SessionMemorySnapshotStage.class);
    private static final int SEARCH_LIMIT = 5;
    private static final int MAX_SNAPSHOT_CHARS = 2_000;

    private final MemoryServiceClient memoryClient;

    public SessionMemorySnapshotStage(MemoryServiceClient memoryClient) {
        this.memoryClient = memoryClient;
    }

    @Override
    public void apply(ContextBuildRequest request, ContextBuildState state) {
        // SessionHistoryStage 已查询过会话，非首轮直接复用其结论，避免额外 HTTP 调用。
        if (state.sessionMessageCount() > 0) {
            return;
        }
        try {
            if (request.currentMessage() == null || request.currentMessage().isBlank()) {
                return;
            }
            String result = memoryClient.search(request.currentMessage(), SEARCH_LIMIT);
            if (result == null || result.isBlank() || "{}".equals(result.strip())) {
                return;
            }
            String snapshot = "<context kind=\"memory_snapshot\">\n"
                    + limit(result) + "\n</context>";
            state.messages().add(Message.system(snapshot));
            memoryClient.addMessage(request.session().id(), "system", snapshot, Map.of(
                    "source", "Jarvis",
                    "hidden", true,
                    "display_event", false,
                    "runtime_context", true,
                    "memory_snapshot", true
            ));
            log.debug("会话记忆快照已创建: sessionId={}", request.session().id());
        } catch (Exception e) {
            log.debug("创建会话记忆快照失败（非致命）: {}", e.getMessage());
        }
    }

    private String limit(String value) {
        if (value.length() <= MAX_SNAPSHOT_CHARS) {
            return value;
        }
        return value.substring(0, MAX_SNAPSHOT_CHARS) + "\n[首轮记忆快照已截断]";
    }
}
