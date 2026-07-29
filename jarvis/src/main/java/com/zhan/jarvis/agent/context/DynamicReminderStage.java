package com.zhan.jarvis.agent.context;

public class DynamicReminderStage implements ContextStage {

    @Override
    public void apply(ContextBuildRequest request, ContextBuildState state) {
        // Phase 1 只抽结构，不改变上下文内容。动态 reminder 会在 Phase 2 实现。
    }
}
