package com.zhan.jarvis.artifact;

import cn.hutool.core.util.IdUtil;
import com.zhan.jarvis.session.SessionFileSpaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 会话 Artifact 管理器。
 * 负责把上传文件、生成文件登记到 session 目录下的 artifacts/index.json。
 */
public class ArtifactManager {

    private static final Logger log = LoggerFactory.getLogger(ArtifactManager.class);

    private final SessionFileSpaceManager fileSpaceManager;
    private final ObjectMapper objectMapper;

    public ArtifactManager(SessionFileSpaceManager fileSpaceManager, ObjectMapper objectMapper) {
        this.fileSpaceManager = fileSpaceManager;
        this.objectMapper = objectMapper;
    }

    public ArtifactRecord registerUpload(String sessionId, String userId, String name, String path,
                                         String contentType, long size, String summary,
                                         Map<String, Object> metadata) {
        return register(sessionId, userId, name, path, contentType, size,
                "upload", "user", summary, metadata);
    }

    public ArtifactRecord registerOutput(String sessionId, String userId, String path, String source,
                                         String summary, Map<String, Object> metadata) {
        Path file = Path.of(path == null ? "" : path).toAbsolutePath().normalize();
        long size = safeSize(file);
        return register(sessionId, userId, file.getFileName() != null ? file.getFileName().toString() : "artifact",
                file.toString(), guessContentType(file), size,
                "output", source == null || source.isBlank() ? "agent" : source, summary, metadata);
    }

    public synchronized ArtifactRecord register(String sessionId, String userId, String name, String path,
                                                String contentType, long size, String kind, String source,
                                                String summary, Map<String, Object> metadata) {
        String artifactId = "art_" + IdUtil.fastSimpleUUID();
        var record = new ArtifactRecord(
                artifactId,
                sessionId,
                userId != null ? userId : "",
                name != null && !name.isBlank() ? name : artifactId,
                path != null ? path : "",
                contentType != null ? contentType : "",
                Math.max(size, 0),
                kind != null ? kind : "",
                source != null ? source : "",
                summary != null ? summary : "",
                metadata == null ? Map.of() : new LinkedHashMap<>(metadata),
                Instant.now()
        );
        save(record);
        return record;
    }

    private void save(ArtifactRecord record) {
        Path indexFile = indexFile(record.sessionId());
        try {
            var artifacts = readAll(indexFile);
            artifacts.add(recordToMap(record));
            var root = objectMapper.createObjectNode();
            var array = root.putArray("artifacts");
            for (Map<String, Object> item : artifacts) {
                array.addPOJO(item);
            }
            Files.writeString(indexFile, objectMapper.writeValueAsString(root));
            log.info("Artifact 已登记: sessionId={}, artifactId={}, path={}",
                    record.sessionId(), record.artifactId(), record.path());
        } catch (Exception e) {
            log.warn("登记 Artifact 失败: sessionId={}, path={}, error={}",
                    record.sessionId(), record.path(), e.getMessage());
            log.debug("登记 Artifact 失败详情", e);
        }
    }

    private List<Map<String, Object>> readAll(Path indexFile) {
        if (!Files.exists(indexFile)) {
            return new ArrayList<>();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(indexFile));
            JsonNode artifacts = root.path("artifacts");
            var result = new ArrayList<Map<String, Object>>();
            if (artifacts.isArray()) {
                for (JsonNode node : artifacts) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> item = objectMapper.treeToValue(node, Map.class);
                    result.add(new LinkedHashMap<>(item));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("读取 Artifact 索引失败，将重建索引: file={}, error={}", indexFile, e.getMessage());
            return new ArrayList<>();
        }
    }

    private Path indexFile(String sessionId) {
        var space = fileSpaceManager.ensure(sessionId);
        Path artifactsDir = space.root().resolve("artifacts").normalize();
        try {
            Files.createDirectories(artifactsDir);
        } catch (Exception e) {
            throw new IllegalStateException("无法创建 Artifact 目录: " + artifactsDir, e);
        }
        return artifactsDir.resolve("index.json");
    }

    private Map<String, Object> recordToMap(ArtifactRecord record) {
        var map = new LinkedHashMap<String, Object>();
        map.put("artifact_id", record.artifactId());
        map.put("session_id", record.sessionId());
        map.put("user_id", record.userId());
        map.put("name", record.name());
        map.put("path", record.path());
        map.put("content_type", record.contentType());
        map.put("size", record.size());
        map.put("kind", record.kind());
        map.put("source", record.source());
        map.put("summary", record.summary());
        map.put("metadata", record.metadata());
        map.put("created_at", record.createdAt().toString());
        return map;
    }

    private long safeSize(Path file) {
        try {
            return Files.exists(file) ? Files.size(file) : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String guessContentType(Path file) {
        try {
            String probed = Files.probeContentType(file);
            if (probed != null && !probed.isBlank()) {
                return probed;
            }
        } catch (Exception ignored) {
            // 使用扩展名兜底。
        }
        String lower = file.getFileName() != null
                ? file.getFileName().toString().toLowerCase(Locale.ROOT)
                : "";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }
}
