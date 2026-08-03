package com.zhan.jarvis.agent.middleware;

import com.zhan.jarvis.agent.loop.LoopState;
import com.zhan.jarvis.llm.ChatResponse;
import com.zhan.jarvis.llm.ToolDefinition;

import java.util.List;

/**
 * AgentLoop 横切能力扩展点。
 * 用于承载预算控制、记忆注入、视觉输入、工具结果压缩、安全策略等运行时逻辑。
 */
public interface AgentMiddleware {

    default String name() {
        return getClass().getSimpleName();
    }

    default void beforeModel(LoopState state, int iteration, List<ToolDefinition> tools) {
    }

    default void afterModel(LoopState state, int iteration, ChatResponse response) {
    }
}
