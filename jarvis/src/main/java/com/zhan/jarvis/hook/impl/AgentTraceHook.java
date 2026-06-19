package com.zhan.jarvis.hook.impl;

import com.zhan.jarvis.hook.Hook;
import com.zhan.jarvis.hook.HookContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 运行轨迹 Hook。
 * 记录用户输入进入 Agent 和最终回复生成事件。
 */
public class AgentTraceHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceHook.class);
    private static final int MAX_PREVIEW_LENGTH = 500;

    @Override
    public String name() {
        return "agent-trace";
    }

    @Override
    public void execute(HookContext ctx) {
        if ("agent.pre_process".equals(ctx.eventType())) {
            log.info("[AgentTrace] pre_process sessionId={}, userId={}, workspace={}, message={}",
                    ctx.sessionId(), ctx.userId(),
                    ctx.payload().getOrDefault("workspace", ""),
                    preview(String.valueOf(ctx.payload().getOrDefault("message", ""))));
            return;
        }

        if ("agent.post_process".equals(ctx.eventType())) {
            log.info("[AgentTrace] post_process sessionId={}, userId={}, iteration={}, finishReason={}, maxIterationsReached={}, reply={}",
                    ctx.sessionId(), ctx.userId(),
                    ctx.payload().getOrDefault("iteration", ""),
                    ctx.payload().getOrDefault("finish_reason", ""),
                    ctx.payload().getOrDefault("max_iterations_reached", ""),
                    preview(String.valueOf(ctx.payload().getOrDefault("reply", ""))));
        }
    }

    private static String preview(String value) {
        if (value == null || value.length() <= MAX_PREVIEW_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_PREVIEW_LENGTH) + "...";
    }
}
