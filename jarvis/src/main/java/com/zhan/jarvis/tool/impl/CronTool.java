package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.cron.CronJob;
import com.zhan.jarvis.cron.CronService;
import com.zhan.jarvis.tool.McpTool;
import com.zhan.jarvis.tool.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * 管理 Cron 定时任务。
 * 对标 Jarvis cron tool。
 */
public class CronTool implements McpTool {

    private final ObjectMapper mapper;
    private final CronService cronService;

    public CronTool(ObjectMapper mapper, CronService cronService) {
        this.mapper = mapper;
        this.cronService = cronService;
    }

    @Override public String name() { return "cron"; }

    @Override public String description() {
        return "创建、列出、删除或立即运行定时任务。支持 at、every_seconds、cron_expr 三种调度方式。";
    }

    @Override public JsonNode inputSchema() {
        var schema = mapper.createObjectNode().put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("action")
                .put("type", "string")
                .put("description", "操作: add/list/remove/enable/disable/run/status");
        props.putObject("name").put("type", "string").put("description", "任务名称，add 时使用");
        props.putObject("message").put("type", "string").put("description", "到期后提交给 Agent 的任务内容");
        props.putObject("at").put("type", "string").put("description", "一次性执行时间，ISO datetime");
        props.putObject("every_seconds").put("type", "integer").put("description", "间隔执行秒数");
        props.putObject("cron_expr").put("type", "string").put("description", "cron 表达式，例如 0 9 * * *");
        props.putObject("tz").put("type", "string").put("description", "cron 表达式时区，例如 Asia/Shanghai");
        props.putObject("job_id").put("type", "string").put("description", "任务 ID，remove/enable/disable/run 时使用");
        props.putObject("session_id").put("type", "string").put("description", "任务使用的会话 ID，默认当前会话");
        props.putObject("deliver").put("type", "boolean").put("description", "任务完成后是否回投到原 Channel，默认 true");
        props.putObject("include_disabled").put("type", "boolean").put("description", "list 时是否包含禁用任务");
        props.putObject("delete_after_run").put("type", "boolean").put("description", "at 任务执行后是否删除");
        props.putObject("force").put("type", "boolean").put("description", "run 时是否强制运行禁用任务");
        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        String action = stringArg(arguments, "action", "list");
        try {
            return switch (action) {
                case "add" -> add(arguments, ctx);
                case "list" -> writeJson(Map.of("jobs", cronService.listJobs(boolArg(arguments, "include_disabled", false))));
                case "remove" -> writeJson(Map.of("removed", cronService.removeJob(required(arguments, "job_id"))));
                case "enable" -> enable(arguments, true);
                case "disable" -> enable(arguments, false);
                case "run" -> {
                    if (!cronService.isRunning()) {
                        yield "Cron 服务未启用，无法运行任务。请设置 JARVIS_CRON_ENABLED=true 并重启 Jarvis。";
                    }
                    yield writeJson(Map.of("started", cronService.runJob(required(arguments, "job_id"),
                            boolArg(arguments, "force", false))));
                }
                case "status" -> writeJson(cronService.status());
                default -> "错误: 不支持的 cron action: " + action;
            };
        } catch (Exception e) {
            return "Cron 操作失败: " + e.getMessage();
        }
    }

    private String add(Map<String, Object> arguments, ToolContext ctx) throws Exception {
        if (!cronService.isRunning()) {
            return "Cron 服务未启用，任务不会被触发。请设置 JARVIS_CRON_ENABLED=true 并重启 Jarvis。";
        }
        String message = required(arguments, "message");
        var schedule = buildSchedule(arguments);
        var sessionKey = ctx.sessionKey();
        String sessionId = stringArg(arguments, "session_id", null);
        if (sessionId != null && !sessionId.isBlank() && !sessionId.equals(ctx.sessionId())) {
            sessionKey = new com.zhan.jarvis.channel.SessionKey(
                    ctx.sessionKey().channelType(),
                    ctx.sessionKey().channelId(),
                    sessionId
            );
        }
        var job = cronService.addJob(
                stringArg(arguments, "name", null),
                schedule,
                message,
                sessionKey,
                boolArg(arguments, "deliver", true),
                boolArg(arguments, "delete_after_run", "at".equals(schedule.kind))
        );
        return writeJson(Map.of("job", job));
    }

    private String enable(Map<String, Object> arguments, boolean enabled) throws Exception {
        var job = cronService.enableJob(required(arguments, "job_id"), enabled);
        if (job == null) {
            return writeJson(Map.of("found", false));
        }
        return writeJson(Map.of("found", true, "job", job));
    }

    private CronJob.CronSchedule buildSchedule(Map<String, Object> arguments) {
        var schedule = new CronJob.CronSchedule();
        String at = stringArg(arguments, "at", null);
        Number everySeconds = numberArg(arguments, "every_seconds");
        String cronExpr = stringArg(arguments, "cron_expr", null);

        if (at != null && !at.isBlank()) {
            schedule.kind = "at";
            schedule.atMs = parseInstant(at).toEpochMilli();
            return schedule;
        }
        if (everySeconds != null) {
            schedule.kind = "every";
            schedule.everyMs = everySeconds.longValue() * 1000;
            return schedule;
        }
        if (cronExpr != null && !cronExpr.isBlank()) {
            schedule.kind = "cron";
            schedule.expr = cronExpr;
            schedule.tz = stringArg(arguments, "tz", null);
            return schedule;
        }
        throw new IllegalArgumentException("add 操作必须提供 at、every_seconds 或 cron_expr");
    }

    private String writeJson(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    private static String required(Map<String, Object> arguments, String key) {
        String value = stringArg(arguments, key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少 " + key + " 参数");
        }
        return value;
    }

    private static String stringArg(Map<String, Object> arguments, String key, String defaultValue) {
        Object value = arguments.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static Number numberArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof Number n) {
            return n;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Long.parseLong(s);
        }
        return null;
    }

    private static boolean boolArg(Map<String, Object> arguments, String key, boolean defaultValue) {
        Object value = arguments.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return defaultValue;
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
        }
    }
}
