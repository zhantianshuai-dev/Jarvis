package com.zhan.jarvis.hook.impl;

import com.zhan.jarvis.hook.Hook;
import com.zhan.jarvis.hook.HookContext;
import com.zhan.jarvis.hook.HookResult;

import java.util.Map;
import java.util.Set;

/**
 * Git 工具策略。
 * 仅允许 admin 或指定飞书 open_id 使用 Git 工具。
 * confirm 只能由后端在人类确认后携带内部标记调用，LLM 直接调用会被拒绝。
 */
public class GitPolicyHook implements Hook {

    private static final String ADMIN_USER = "admin";
    private static final String FEISHU_ADMIN_OPEN_ID = "ou_5e05efe345547cb07ebd9705599c6503";
    public static final String META_HUMAN_CONFIRMED = "human_confirmed";
    private static final Set<String> DENIED_ACTIONS = Set.of(
            "reset",
            "clean",
            "checkout",
            "force_push"
    );

    @Override
    public String name() {
        return "git-policy";
    }

    @Override
    public HookResult evaluate(HookContext ctx) {
        String toolName = String.valueOf(ctx.payload().getOrDefault("tool_name", ""));
        if (!"git".equals(toolName)) {
            return HookResult.allow();
        }

        Object rawArgs = ctx.payload().get("arguments");
        if (!(rawArgs instanceof Map<?, ?> args)) {
            return HookResult.deny("Git 工具缺少参数，拒绝执行");
        }

        String action = value(args.get("action"));
        if (DENIED_ACTIONS.contains(action)) {
            return HookResult.deny("Git 高风险操作被拒绝: " + action);
        }

        Object metadata = ctx.payload().get("metadata");
        String channelType = value(ctx.payload().get("channel_type"));
        String userId = value(ctx.userId());
        if ("feishu".equals(channelType)) {
            String senderId = "";
            if (metadata instanceof Map<?, ?> map) {
                senderId = value(map.get("sender_id"));
            }
            if (FEISHU_ADMIN_OPEN_ID.equals(senderId) || FEISHU_ADMIN_OPEN_ID.equals(userId)) {
                return allowAfterConfirmCheck(action, metadata);
            }
            return HookResult.deny("当前飞书用户无权使用 Git 工具: " + firstNonBlank(senderId, userId));
        }

        if (ADMIN_USER.equals(userId)) {
            return allowAfterConfirmCheck(action, metadata);
        }

        return HookResult.deny("当前用户无权使用 Git 工具: " + userId);
    }

    private HookResult allowAfterConfirmCheck(String action, Object metadata) {
        //这里判断，如果action不是confirm，就直接allow
        if (!"confirm".equals(action)) {
            return HookResult.allow();
        }
        //如果是confirm，那就去map中获取META_HUMAN_CONFIRMED，是否有这个
        if (metadata instanceof Map<?, ?> map && Boolean.parseBoolean(value(map.get(META_HUMAN_CONFIRMED)))) {
            return HookResult.allow();
        }
        return HookResult.deny("Git confirm 只能由后端在人类确认后触发，拒绝 LLM 直接确认");
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? value(second) : first;
    }
}
