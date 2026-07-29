package com.zhan.jarvis.agent.context;

import com.zhan.jarvis.llm.Message;
import com.zhan.jarvis.memory.MemoryServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MemoryRetrievalStage implements ContextStage {

    private static final Logger log = LoggerFactory.getLogger(MemoryRetrievalStage.class);

    private final MemoryServiceClient memoryClient;

    public MemoryRetrievalStage(MemoryServiceClient memoryClient) {
        this.memoryClient = memoryClient;
    }

    @Override
    public void apply(ContextBuildRequest request, ContextBuildState state) {
        try {
            String memoryResult = memoryClient.search(request.currentMessage(), 5);
            if (memoryResult != null && !memoryResult.isBlank() && !memoryResult.equals("{}")) {
                state.messages().add(Message.user(
                        "<memory_context>\n" + memoryResult + "\n</memory_context>\n\n"
                                + "以上是从记忆系统中检索到的相关上下文。请参考这些信息回复用户的以下消息：\n\n"
                                + request.currentMessage()));
                state.markCurrentMessageAdded();
            }
        } catch (Exception e) {
            log.debug("记忆检索失败（非致命）: {}", e.getMessage());
        }
    }
}
