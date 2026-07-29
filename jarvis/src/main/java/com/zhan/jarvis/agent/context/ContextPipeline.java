package com.zhan.jarvis.agent.context;

import com.zhan.jarvis.llm.Message;

import java.util.List;

public class ContextPipeline {

    private final List<ContextStage> stages;

    public ContextPipeline(List<ContextStage> stages) {
        this.stages = List.copyOf(stages);
    }

    public List<Message> build(ContextBuildRequest request) {
        var state = new ContextBuildState();
        for (var stage : stages) {
            stage.apply(request, state);
        }
        return state.messages();
    }
}
