package com.zhan.jarvis.agent.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * JSONL 运行事件存储。
 * 每行一条事件，方便本地排查和后续离线回放。
 */
public class JsonlRunEventStore implements RunEventStore {

    private static final Logger log = LoggerFactory.getLogger(JsonlRunEventStore.class);

    private final Path eventsFile;
    private final ObjectMapper objectMapper;

    public JsonlRunEventStore(Path workspaceDir, ObjectMapper objectMapper) {
        Path root = workspaceDir == null ? Path.of(".") : workspaceDir;
        this.eventsFile = root.toAbsolutePath().normalize().resolve("runs").resolve("events.jsonl");
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(eventsFile.getParent());
        } catch (Exception e) {
            throw new IllegalStateException("无法创建运行事件目录: " + eventsFile.getParent(), e);
        }
        log.info("RunEventStore 初始化: file={}", eventsFile);
    }

    @Override
    public synchronized void append(RunEvent event) {
        if (event == null) {
            return;
        }
        try {
            Files.writeString(eventsFile, objectMapper.writeValueAsString(event) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("写入运行事件失败: type={}, runId={}, error={}",
                    event.type(), event.runId(), e.getMessage());
            log.debug("写入运行事件失败详情", e);
        }
    }

    public Path eventsFile() {
        return eventsFile;
    }
}
