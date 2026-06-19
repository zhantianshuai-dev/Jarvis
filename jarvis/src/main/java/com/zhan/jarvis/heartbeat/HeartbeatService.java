package com.zhan.jarvis.heartbeat;

import cn.hutool.core.util.IdUtil;
import com.zhan.jarvis.bus.InboundMessage;
import com.zhan.jarvis.bus.MessageBus;
import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.config.JarvisConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Heartbeat 自主唤醒服务。
 * 定时读取 workspace 下的 HEARTBEAT.md，把其中的任务投递给 Agent。
 */
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);
    private static final String CHANNEL_TYPE = "heartbeat";
    private static final String CHANNEL_ID = "default";
    private static final String USER_ID = "system-heartbeat";

    private final JarvisConfig.HeartbeatConfig config;
    private final String workspaceDir;
    private final MessageBus messageBus;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    public HeartbeatService(JarvisConfig.HeartbeatConfig config, String workspaceDir, MessageBus messageBus) {
        this.config = config;
        this.workspaceDir = workspaceDir;
        this.messageBus = messageBus;
    }

    public void start() {
        if (config == null || !config.enabled()) {
            log.info("HeartbeatService 未启用");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread.startVirtualThread(this::runLoop);
        log.info("HeartbeatService 已启动: interval={}s, file={}",
                interval().toSeconds(), heartbeatFile());
    }

    public void stop() {
        running.set(false);
    }

    private void runLoop() {
        while (running.get()) {
            try {
                tick();
                Thread.sleep(interval());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running.set(false);
            } catch (Exception e) {
                log.warn("HeartbeatService 执行异常: {}", e.getMessage());
                log.debug("HeartbeatService 执行异常详情", e);
            }
        }
    }

    private void tick() throws IOException {
        Path file = heartbeatFile();
        if (!Files.exists(file)) {
            return;
        }

        String content = Files.readString(file).trim();
        if (content.isBlank()) {
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            log.debug("Heartbeat 上一轮仍在处理中，跳过本轮");
            return;
        }

        String sessionId = defaultString(config.sessionId(), "heartbeat-default");
        String messageId = "heartbeat_" + IdUtil.getSnowflake(1, 1).nextId();
        var sessionKey = new SessionKey(CHANNEL_TYPE, CHANNEL_ID, sessionId);
        var inbound = InboundMessage.of(messageId, sessionKey, sessionId, USER_ID, content);

        messageBus.submit(inbound).whenComplete((outbound, error) -> {
            inFlight.set(false);
            if (error != null) {
                log.warn("Heartbeat 任务执行失败: id={}, error={}", messageId, error.getMessage());
            } else {
                log.info("Heartbeat 任务执行完成: id={}, sessionId={}", messageId, sessionId);
            }
        });
        log.info("Heartbeat 任务已提交: id={}, sessionId={}", messageId, sessionId);
    }

    private Duration interval() {
        long seconds = config.intervalSeconds() > 0 ? config.intervalSeconds() : 1800;
        return Duration.ofSeconds(seconds);
    }

    private Path heartbeatFile() {
        String fileName = defaultString(config.file(), "HEARTBEAT.md");
        Path file = Path.of(fileName);
        if (file.isAbsolute()) {
            return file.normalize();
        }
        return Path.of(workspaceDir).resolve(file).normalize();
    }

    private static String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
