package com.zhan.jarvis.agent.middleware;

import com.zhan.jarvis.agent.loop.LoopState;
import com.zhan.jarvis.llm.ChatResponse;
import com.zhan.jarvis.llm.ToolDefinition;

import java.util.List;

/**
 * 按固定顺序执行 AgentMiddleware。
 * 第一版只接入模型调用前后两个节点，后续再扩展工具调用、循环开始/结束等节点。
 */
public class AgentMiddlewareChain {

    public static final AgentMiddlewareChain EMPTY = new AgentMiddlewareChain(List.of());

    private final List<AgentMiddleware> middlewares;

    public AgentMiddlewareChain(List<AgentMiddleware> middlewares) {
        this.middlewares = middlewares == null ? List.of() : List.copyOf(middlewares);
    }

    public void beforeModel(LoopState state, int iteration, List<ToolDefinition> tools) {
        for (AgentMiddleware middleware : middlewares) {
            middleware.beforeModel(state, iteration, tools);
        }
    }

    public void afterModel(LoopState state, int iteration, ChatResponse response) {
        for (AgentMiddleware middleware : middlewares) {
            middleware.afterModel(state, iteration, response);
        }
    }

    public List<AgentMiddleware> middlewares() {
        return middlewares;
    }
}
