package com.zhan.jarvis.agent.context;

public interface ContextStage {

    void apply(ContextBuildRequest request, ContextBuildState state);
}
