package com.zhan.jarvis.agent.context;

import com.zhan.jarvis.llm.Message;

public class CurrentMessageStage implements ContextStage {

    @Override
    public void apply(ContextBuildRequest request, ContextBuildState state) {
        if (!state.currentMessageAdded()) {
            state.messages().add(Message.user(request.currentMessage()));
            state.markCurrentMessageAdded();
        }
    }
}
