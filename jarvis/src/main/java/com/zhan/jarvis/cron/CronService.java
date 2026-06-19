package com.zhan.jarvis.cron;

import cn.hutool.core.util.IdUtil;
import com.zhan.jarvis.bus.InboundMessage;
import com.zhan.jarvis.bus.MessageBus;
import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.config.JarvisConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cron 定时任务服务。
 * 对齐 Jarvis：使用持久化 CronStore，计算 nextRunAtMs，并按最近任务时间唤醒。
 */
public class CronService {

    private static final Logger log = LoggerFactory.getLogger(CronService.class);
    private static final String USER_ID = "system-cron";

    private final JarvisConfig.CronConfig config;
    private final String workspaceDir;
    private final MessageBus messageBus;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object monitor = new Object();

    private CronJob.CronStore store;
    private Thread timerThread;

    public CronService(JarvisConfig.CronConfig config, String workspaceDir,
                       MessageBus messageBus, ObjectMapper objectMapper) {
        this.config = config;
        this.workspaceDir = workspaceDir;
        this.messageBus = messageBus;
        this.objectMapper = objectMapper;
    }

    public void start() {
        if (config == null || !config.enabled()) {
            log.info("CronService 未启用");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        synchronized (monitor) {
            loadStore();
            recomputeNextRuns();
            saveStore();
            timerThread = Thread.startVirtualThread(this::runLoop);
        }
        log.info("CronService 已启动: file={}", storeFile());
    }

    public void stop() {
        running.set(false);
        if (timerThread != null) {
            timerThread.interrupt();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public List<CronJob> listJobs(boolean includeDisabled) {
        synchronized (monitor) {
            var current = loadStore();
            return current.jobs.stream()
                    .filter(job -> includeDisabled || job.enabled)
                    .sorted(Comparator.comparing(job -> job.state.nextRunAtMs == null
                            ? Long.MAX_VALUE
                            : job.state.nextRunAtMs))
                    .toList();
        }
    }

    public CronJob addJob(String name, CronJob.CronSchedule schedule, String message,
                          SessionKey sessionKey, boolean deliver, boolean deleteAfterRun) {
        synchronized (monitor) {
            var current = loadStore();
            long now = nowMs();
            var job = new CronJob();
            job.id = UUID.randomUUID().toString().substring(0, 8);
            job.name = name == null || name.isBlank() ? job.id : name;
            job.enabled = true;
            job.schedule = schedule;
            job.payload.kind = "agent_turn";
            job.payload.message = message == null ? "" : message;
            job.payload.deliver = deliver;
            job.payload.sessionKeyStr = encodeSessionKey(sessionKey);
            job.state.nextRunAtMs = computeNextRun(schedule, now);
            job.createdAtMs = now;
            job.updatedAtMs = now;
            job.deleteAfterRun = deleteAfterRun;

            current.jobs.add(job);
            saveStore();
            wakeTimer();
            log.info("Cron: added job '{}' ({})", job.name, job.id);
            return job;
        }
    }

    public boolean removeJob(String jobId) {
        synchronized (monitor) {
            var current = loadStore();
            int before = current.jobs.size();
            current.jobs.removeIf(job -> job.id.equals(jobId));
            boolean removed = current.jobs.size() < before;
            if (removed) {
                saveStore();
                wakeTimer();
                log.info("Cron: removed job {}", jobId);
            }
            return removed;
        }
    }

    public CronJob enableJob(String jobId, boolean enabled) {
        synchronized (monitor) {
            var job = findJob(jobId);
            if (job == null) {
                return null;
            }
            job.enabled = enabled;
            job.updatedAtMs = nowMs();
            job.state.nextRunAtMs = enabled ? computeNextRun(job.schedule, nowMs()) : null;
            saveStore();
            wakeTimer();
            return job;
        }
    }

    public boolean runJob(String jobId, boolean force) {
        synchronized (monitor) {
            var job = findJob(jobId);
            if (job == null || (!force && !job.enabled)) {
                return false;
            }
            executeJob(job);
            saveStore();
            wakeTimer();
            return true;
        }
    }

    public Map<String, Object> status() {
        synchronized (monitor) {
            var current = loadStore();
            return Map.of(
                    "enabled", running.get(),
                    "jobs", current.jobs.size(),
                    "next_wake_at_ms", getNextWakeMs() == null ? "" : getNextWakeMs()
            );
        }
    }

    private void runLoop() {
        while (running.get()) {
            try {
                synchronized (monitor) {
                    runDueJobs();
                    saveStore();
                }
                sleepUntilNextWake();
            } catch (InterruptedException e) {
                if (running.get()) {
                    continue;
                }
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("CronService 执行异常: {}", e.getMessage());
                log.debug("CronService 执行异常详情", e);
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void runDueJobs() {
        var current = loadStore();
        long now = nowMs();
        var dueJobs = current.jobs.stream()
                .filter(job -> job.enabled && job.state.nextRunAtMs != null && now >= job.state.nextRunAtMs)
                .toList();
        for (var job : dueJobs) {
            executeJob(job);
        }
    }

    private void executeJob(CronJob job) {
        long startMs = nowMs();
        log.info("Cron: executing job '{}' ({})", job.name, job.id);
        try {
            String messageId = "cron_" + job.id + "_" + IdUtil.getSnowflake(1, 1).nextId();
            var sessionKey = decodeSessionKey(job.payload.sessionKeyStr);
            String sessionId = sessionKey.chatId();
            var inbound = InboundMessage.of(messageId, sessionKey, sessionId, USER_ID,
                    buildCronInstruction(job), Map.of("auto_deliver", job.payload.deliver));
            messageBus.submit(inbound).whenComplete((outbound, error) -> {
                if (error != null) {
                    log.warn("CronJob 异步执行失败: id={}, messageId={}, error={}",
                            job.id, messageId, error.getMessage());
                } else {
                    log.info("CronJob 异步执行完成: id={}, messageId={}", job.id, messageId);
                }
            });

            job.state.lastStatus = "ok";
            job.state.lastError = null;
        } catch (Exception e) {
            job.state.lastStatus = "error";
            job.state.lastError = e.getMessage();
            log.warn("Cron: job '{}' failed: {}", job.name, e.getMessage());
            log.debug("Cron job 执行异常详情", e);
        }

        job.state.lastRunAtMs = startMs;
        job.updatedAtMs = nowMs();
        if ("at".equals(job.schedule.kind)) {
            if (job.deleteAfterRun) {
                loadStore().jobs.removeIf(item -> item.id.equals(job.id));
            } else {
                job.enabled = false;
                job.state.nextRunAtMs = null;
            }
        } else {
            job.state.nextRunAtMs = computeNextRun(job.schedule, nowMs());
        }
    }

    private String buildCronInstruction(CronJob job) {
        return """
                [CRON TASK]
                This is a scheduled task triggered by cron job: '%s'
                Your task is to deliver the following reminder message to the user.

                IMPORTANT:
                - This is NOT a user message - it is a scheduled reminder or scheduled task.
                - You should acknowledge or confirm the reminder in a friendly way.
                - DO NOT treat this as a question from the user unless the reminder message explicitly asks you to do work.
                - If the message describes a task, execute the task and report the result.

                Reminder message to deliver:
                \"\"\"%s\"\"\"
                """.formatted(job.name, job.payload.message == null ? "" : job.payload.message);
    }

    private String encodeSessionKey(SessionKey sessionKey) {
        var key = sessionKey != null ? sessionKey : new SessionKey("http", "default", "cron-default");
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "channelType", key.channelType(),
                    "channelId", key.channelId(),
                    "chatId", key.chatId()
            ));
        } catch (Exception e) {
            return key.chatId();
        }
    }

    private SessionKey decodeSessionKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return new SessionKey("http", "default", "cron-default");
        }
        try {
            var node = objectMapper.readTree(raw);
            if (node != null && node.isObject()) {
                return new SessionKey(
                        text(node, "channelType", text(node, "type", "http")),
                        text(node, "channelId", text(node, "id", "default")),
                        text(node, "chatId", text(node, "sessionId", "cron-default"))
                );
            }
        } catch (Exception ignored) {
            // 兼容旧版本：session_key_str 可能只是 sessionId。
        }
        return new SessionKey("http", "default", raw);
    }

    private CronJob.CronStore loadStore() {
        if (store != null) {
            return store;
        }
        Path file = storeFile();
        if (!Files.exists(file)) {
            store = new CronJob.CronStore();
            return store;
        }
        try {
            JsonNode root = objectMapper.readTree(file.toFile());
            var loaded = new CronJob.CronStore();
            loaded.version = root.has("version") ? root.get("version").asInt() : 1;
            if (root.has("jobs") && root.get("jobs").isArray()) {
                for (var node : root.get("jobs")) {
                    loaded.jobs.add(parseJob(node));
                }
            }
            store = loaded;
        } catch (Exception e) {
            log.warn("加载 CronStore 失败，将使用空 store: {}", e.getMessage());
            store = new CronJob.CronStore();
        }
        return store;
    }

    private CronJob parseJob(JsonNode node) {
        var job = new CronJob();
        job.id = text(node, "id", UUID.randomUUID().toString().substring(0, 8));
        job.name = text(node, "name", job.id);
        job.enabled = !node.has("enabled") || node.get("enabled").asBoolean();
        job.createdAtMs = longValue(node, "createdAtMs", nowMs());
        job.updatedAtMs = longValue(node, "updatedAtMs", job.createdAtMs);
        job.deleteAfterRun = node.has("deleteAfterRun") && node.get("deleteAfterRun").asBoolean();

        JsonNode scheduleNode = node.get("schedule");
        if (scheduleNode != null && scheduleNode.isObject()) {
            job.schedule.kind = text(scheduleNode, "kind", "every");
            job.schedule.atMs = nullableLong(scheduleNode, "atMs");
            job.schedule.everyMs = nullableLong(scheduleNode, "everyMs");
            job.schedule.expr = text(scheduleNode, "expr", null);
            job.schedule.tz = text(scheduleNode, "tz", null);
        }

        JsonNode payloadNode = node.get("payload");
        if (payloadNode != null && payloadNode.isObject()) {
            job.payload.kind = text(payloadNode, "kind", "agent_turn");
            job.payload.message = text(payloadNode, "message", "");
            job.payload.deliver = payloadNode.has("deliver") && payloadNode.get("deliver").asBoolean();
            job.payload.sessionKeyStr = text(payloadNode, "session_key_str", "cron-default");
        }

        JsonNode stateNode = node.get("state");
        if (stateNode != null && stateNode.isObject()) {
            job.state.nextRunAtMs = nullableLong(stateNode, "nextRunAtMs");
            job.state.lastRunAtMs = nullableLong(stateNode, "lastRunAtMs");
            job.state.lastStatus = text(stateNode, "lastStatus", null);
            job.state.lastError = text(stateNode, "lastError", null);
        }
        return job;
    }

    private void saveStore() {
        if (store == null) {
            return;
        }
        try {
            Path file = storeFile();
            Files.createDirectories(file.getParent());
            var root = new LinkedHashMap<String, Object>();
            root.put("version", store.version);
            root.put("jobs", store.jobs.stream().map(this::toJsonMap).toList());
            Files.writeString(file, objectMapper.writeValueAsString(root));
        } catch (IOException e) {
            log.warn("保存 CronStore 失败: {}", e.getMessage());
        }
    }

    private Map<String, Object> toJsonMap(CronJob job) {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", job.id);
        map.put("name", job.name);
        map.put("enabled", job.enabled);
        var schedule = new LinkedHashMap<String, Object>();
        schedule.put("kind", job.schedule.kind);
        schedule.put("atMs", job.schedule.atMs);
        schedule.put("everyMs", job.schedule.everyMs);
        schedule.put("expr", job.schedule.expr);
        schedule.put("tz", job.schedule.tz);
        map.put("schedule", schedule);

        var payload = new LinkedHashMap<String, Object>();
        payload.put("kind", job.payload.kind);
        payload.put("message", job.payload.message);
        payload.put("deliver", job.payload.deliver);
        payload.put("session_key_str", job.payload.sessionKeyStr);
        map.put("payload", payload);

        var state = new LinkedHashMap<String, Object>();
        state.put("nextRunAtMs", job.state.nextRunAtMs);
        state.put("lastRunAtMs", job.state.lastRunAtMs);
        state.put("lastStatus", job.state.lastStatus);
        state.put("lastError", job.state.lastError);
        map.put("state", state);
        map.put("createdAtMs", job.createdAtMs);
        map.put("updatedAtMs", job.updatedAtMs);
        map.put("deleteAfterRun", job.deleteAfterRun);
        return map;
    }

    private void recomputeNextRuns() {
        long now = nowMs();
        for (var job : loadStore().jobs) {
            if (!job.enabled) {
                job.state.nextRunAtMs = null;
            } else if (job.state.nextRunAtMs == null) {
                job.state.nextRunAtMs = computeNextRun(job.schedule, now);
            }
        }
    }

    private Long computeNextRun(CronJob.CronSchedule schedule, long nowMs) {
        if ("at".equals(schedule.kind)) {
            return schedule.atMs != null && schedule.atMs > nowMs ? schedule.atMs : null;
        }
        if ("every".equals(schedule.kind)) {
            return schedule.everyMs != null && schedule.everyMs > 0 ? nowMs + schedule.everyMs : null;
        }
        if ("cron".equals(schedule.kind) && schedule.expr != null && !schedule.expr.isBlank()) {
            try {
                var zone = schedule.tz == null || schedule.tz.isBlank()
                        ? ZoneId.systemDefault()
                        : ZoneId.of(schedule.tz);
                var expression = CronExpression.parse(schedule.expr);
                LocalDateTime next = expression.next(LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone));
                return next == null ? null : next.atZone(zone).toInstant().toEpochMilli();
            } catch (Exception e) {
                log.warn("Cron 表达式无效: expr={}, error={}", schedule.expr, e.getMessage());
            }
        }
        return null;
    }

    private Long getNextWakeMs() {
        return loadStore().jobs.stream()
                .filter(job -> job.enabled && job.state.nextRunAtMs != null)
                .map(job -> job.state.nextRunAtMs)
                .min(Long::compareTo)
                .orElse(null);
    }

    private void sleepUntilNextWake() throws InterruptedException {
        Long nextWake = getNextWakeMs();
        if (nextWake == null) {
            Thread.sleep(60_000);
            return;
        }
        long delayMs = Math.max(0, nextWake - nowMs());
        Thread.sleep(delayMs);
    }

    private void wakeTimer() {
        if (timerThread != null) {
            timerThread.interrupt();
        }
    }

    private CronJob findJob(String jobId) {
        return loadStore().jobs.stream()
                .filter(job -> job.id.equals(jobId))
                .findFirst()
                .orElse(null);
    }

    private Path storeFile() {
        String fileName = config.file() == null || config.file().isBlank()
                ? "cron/store.json"
                : config.file();
        Path file = Path.of(fileName);
        if (file.isAbsolute()) {
            return file.normalize();
        }
        return Path.of(workspaceDir).resolve(file).normalize();
    }

    private static long nowMs() {
        return System.currentTimeMillis();
    }

    private static String text(JsonNode node, String field, String defaultValue) {
        return node.has(field) && !node.get(field).isNull()
                ? node.get(field).asText()
                : defaultValue;
    }

    private static Long nullableLong(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asLong() : null;
    }

    private static long longValue(JsonNode node, String field, long defaultValue) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asLong() : defaultValue;
    }
}
