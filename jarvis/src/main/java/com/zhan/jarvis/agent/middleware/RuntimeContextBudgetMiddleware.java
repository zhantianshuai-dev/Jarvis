package com.zhan.jarvis.agent.middleware;

import com.zhan.jarvis.agent.loop.LoopState;
import com.zhan.jarvis.agent.loop.RuntimeContextBudgeter;
import com.zhan.jarvis.config.JarvisConfig;
import com.zhan.jarvis.llm.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 运行态上下文预算控制。
 * 在每次请求 LLM 前压缩过长上下文，避免工具 schema 和历史消息无限膨胀。
 */
public class RuntimeContextBudgetMiddleware implements AgentMiddleware {

    private static final Logger log = LoggerFactory.getLogger(RuntimeContextBudgetMiddleware.class);

    private final JarvisConfig.AgentConfig.ContextBudgetConfig contextBudget;
    private final RuntimeContextBudgeter budgeter;

    public RuntimeContextBudgetMiddleware(JarvisConfig.AgentConfig.ContextBudgetConfig contextBudget) {
        this(contextBudget, new RuntimeContextBudgeter());
    }

    public RuntimeContextBudgetMiddleware(JarvisConfig.AgentConfig.ContextBudgetConfig contextBudget,
                                          RuntimeContextBudgeter budgeter) {
        this.contextBudget = contextBudget;
        this.budgeter = budgeter;
    }

    @Override
    public void beforeModel(LoopState state, int iteration, List<ToolDefinition> tools) {
        var budget = budgeter.apply(state.messages(), tools, contextBudget);
        if (budget.changed()) {
            log.info("[AgentLoop] 运行态上下文已压缩: beforeTokens={}, afterTokens={}, compressed={}, removed={}",
                    budget.beforeTokens(), budget.afterTokens(), budget.compressedMessages(), budget.removedMessages());
        } else {
            log.debug("[AgentLoop] 运行态上下文预算: estimatedTokens={}", budget.afterTokens());
        }
    }
}
