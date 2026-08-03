package com.zhan.jarvis.agent.planner;

import com.zhan.jarvis.session.SessionFileSpaceManager;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话级执行计划持久化。
 */
public class PlanManager {

    private final SessionFileSpaceManager fileSpaceManager;
    private final ObjectMapper objectMapper;

    public PlanManager(SessionFileSpaceManager fileSpaceManager, ObjectMapper objectMapper) {
        this.fileSpaceManager = fileSpaceManager;
        this.objectMapper = objectMapper;
    }

    public synchronized void save(ExecutionPlan plan) throws IOException {
        if (plan == null || plan.sessionId() == null || plan.sessionId().isBlank()) {
            return;
        }
        Path file = planFile(plan.sessionId());
        Files.createDirectories(file.getParent());
        objectMapper.writeValue(file.toFile(), payload(plan));
    }

    public Map<String, Object> payload(ExecutionPlan plan) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("plan_id", plan.planId());
        payload.put("run_id", plan.runId());
        payload.put("session_id", plan.sessionId());
        payload.put("mode", plan.runMode() != null ? plan.runMode().value() : "");
        payload.put("goal", plan.goal());
        payload.put("strategy", plan.strategy());
        payload.put("generated_by_llm", plan.generatedByLlm());
        payload.put("created_at", plan.createdAt() != null ? plan.createdAt().toString() : "");
        payload.put("steps", plan.steps().stream().map(step -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("id", step.id());
            item.put("title", step.title());
            item.put("type", step.type() != null ? step.type().value() : "");
            item.put("status", step.status() != null ? step.status().value() : "");
            item.put("tool_groups", step.toolGroups() == null ? java.util.List.of() : step.toolGroups());
            item.put("parallel", step.parallel());
            item.put("parallel_group", step.parallelGroup());
            item.put("requires_confirmation", step.requiresConfirmation());
            item.put("reason", step.reason());
            return item;
        }).toList());
        return payload;
    }

    private Path planFile(String sessionId) {
        return fileSpaceManager.ensure(sessionId).root().resolve("plans").resolve("current.json");
    }
}
