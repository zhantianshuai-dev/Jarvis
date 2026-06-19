package com.zhan.jarvis.hook.impl;

import com.zhan.jarvis.hook.Hook;
import com.zhan.jarvis.hook.HookContext;
import com.zhan.jarvis.hook.HookResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * exec 工具的基础安全策略。
 * 只拦截明显危险的宿主机破坏性命令，避免误伤普通构建、测试和文件操作。
 */
public class ExecSafetyHook implements Hook {

    private static final List<String> DENIED_PATTERNS = List.of(
            // 递归强制删除根目录，可能清空整个文件系统。
            "rm -rf /",
            // rm -rf 的参数顺序变体，同样是递归强制删除根目录。
            "rm -fr /",
            // 格式化文件系统/磁盘分区，会破坏磁盘数据。
            "mkfs",
            // 关闭宿主机。
            "shutdown",
            // 重启宿主机。
            "reboot",
            // 停止宿主机运行。
            "halt",
            // Shell fork bomb，会无限创建进程耗尽系统资源。
            ":(){",
            // 递归开放根目录权限，破坏系统权限边界。
            "chmod -r 777 /",
            // 递归修改文件属主，可能破坏系统和项目文件权限。
            "chown -r"
    );

    @Override
    public String name() {
        return "exec-safety";
    }

    @Override
    public HookResult evaluate(HookContext ctx) {
        String toolName = String.valueOf(ctx.payload().getOrDefault("tool_name", ""));
        if (!"exec".equals(toolName)) {
            return HookResult.allow();
        }

        Object rawArgs = ctx.payload().get("arguments");
        if (!(rawArgs instanceof Map<?, ?> args)) {
            return HookResult.allow();
        }

        Object commandObj = args.get("command");
        String command = commandObj != null ? String.valueOf(commandObj) : "";
        String normalized = command.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        for (String pattern : DENIED_PATTERNS) {
            if (normalized.contains(pattern)) {
                return HookResult.deny("危险 shell 命令被拒绝: " + pattern);
            }
        }
        return HookResult.allow();
    }
}
