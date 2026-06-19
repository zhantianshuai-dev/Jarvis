package com.zhan.jarvis.hook.impl;

import com.zhan.jarvis.hook.Hook;
import com.zhan.jarvis.hook.HookContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具调用审计 Hook。
 * 记录工具调用开始、结束、耗时和成功/失败状态。
 */
public class ToolAuditHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ToolAuditHook.class);
    private static final int MAX_PREVIEW_LENGTH = 500;

    @Override
    public String name() {
        return "tool-audit";
    }

    @Override
    public void execute(HookContext ctx) {
        String toolName = String.valueOf(ctx.payload().getOrDefault("tool_name", ""));
        if ("tool.pre_call".equals(ctx.eventType())) {
            log.info("[ToolAudit] pre_call sessionId={}, userId={}, tool={}, args={}",
                    ctx.sessionId(), ctx.userId(), toolName,
                    preview(String.valueOf(ctx.payload().getOrDefault("arguments", ""))));
            return;
        }

        if ("tool.post_call".equals(ctx.eventType())) {
            Object success = ctx.payload().getOrDefault("success", "");
            Object elapsed = ctx.payload().getOrDefault("elapsed_ms", "");
            Object resultLength = ctx.payload().getOrDefault("result_length", "");
            Object error = ctx.payload().getOrDefault("error", "");
            log.info("[ToolAudit] post_call sessionId={}, userId={}, tool={}, success={}, elapsedMs={}, resultLength={}, error={}",
                    ctx.sessionId(), ctx.userId(), toolName, success, elapsed, resultLength, error);
        }
    }

    private static String preview(String value) {
        if (value == null || value.length() <= MAX_PREVIEW_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_PREVIEW_LENGTH) + "...";
    }
}
