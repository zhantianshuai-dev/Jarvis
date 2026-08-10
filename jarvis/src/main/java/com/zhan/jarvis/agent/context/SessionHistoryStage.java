package com.zhan.jarvis.agent.context;

import com.zhan.jarvis.agent.RunMode;
import com.zhan.jarvis.llm.Message;
import com.zhan.jarvis.memory.MemoryServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionHistoryStage implements ContextStage {

    private static final Logger log = LoggerFactory.getLogger(SessionHistoryStage.class);

    private final MemoryServiceClient memoryClient;

    public SessionHistoryStage(MemoryServiceClient memoryClient) {
        this.memoryClient = memoryClient;
    }

    @Override
    public void apply(ContextBuildRequest request, ContextBuildState state) {
        try {
            var ctx = memoryClient.getSessionContext(request.session().id(), request.historyRounds() * 2);
            state.sessionMessageCount(ctx.messageCount());

            String wm = ctx.workingMemory();
            if (wm != null && !wm.isBlank()) {
                state.messages().add(Message.system("""
                        <context kind="working_memory">
                        %s
                        </context>
                        """.formatted(wm).strip()));
            }

            for (var stub : ctx.messages()) {
                if (request.runMode() == RunMode.CHAT && isTraceRole(stub.role())) {
                    continue;
                }
                state.messages().add(new Message(stub.role(), stub.content(), null, null, null, null));
            }
        } catch (Exception e) {
            log.debug("获取 Session Context 失败（非致命）: {}", e.getMessage());
        }
    }

    private boolean isTraceRole(String role) {
        return "tool".equals(role);
    }
}
