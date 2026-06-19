package com.zhan.jarvis.cron;

import java.util.ArrayList;
import java.util.List;

/**
 * Cron 定时任务定义，对齐 Jarvis CronStore。
 */
public class CronJob {

    public String id;
    public String name;
    public boolean enabled = true;
    public CronSchedule schedule = new CronSchedule();
    public CronPayload payload = new CronPayload();
    public CronJobState state = new CronJobState();
    public long createdAtMs;
    public long updatedAtMs;
    public boolean deleteAfterRun;

    public String safeSessionId() {
        return payload.sessionKeyStr == null || payload.sessionKeyStr.isBlank()
                ? "cron-default"
                : payload.sessionKeyStr;
    }

    public static class CronSchedule {
        public String kind = "every";
        public Long atMs;
        public Long everyMs;
        public String expr;
        public String tz;
    }

    public static class CronPayload {
        public String kind = "agent_turn";
        public String message = "";
        public boolean deliver;
        public String sessionKeyStr;
    }

    public static class CronJobState {
        public Long nextRunAtMs;
        public Long lastRunAtMs;
        public String lastStatus;
        public String lastError;
    }

    public static class CronStore {
        public int version = 1;
        public List<CronJob> jobs = new ArrayList<>();
    }
}
