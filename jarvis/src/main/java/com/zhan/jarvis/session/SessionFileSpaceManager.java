package com.zhan.jarvis.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 会话文件空间管理器。
 * 每个会话拥有独立文件目录，用于后续上传、生成物、临时文件和检查点持久化。
 */
public class SessionFileSpaceManager {

    private static final Logger log = LoggerFactory.getLogger(SessionFileSpaceManager.class);
    private static final Pattern SAFE_SESSION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,127}");

    private final Path sessionsRoot;

    public SessionFileSpaceManager(String workspaceDir) {
        Path workspace = Path.of(workspaceDir == null || workspaceDir.isBlank() ? "." : workspaceDir)
                .toAbsolutePath()
                .normalize();
        this.sessionsRoot = workspace.resolve("sessions").normalize();
        try {
            Files.createDirectories(sessionsRoot);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建会话文件根目录: " + sessionsRoot, e);
        }
        log.info("SessionFileSpaceManager 初始化: root={}", sessionsRoot);
    }

    public SessionFileSpace ensure(String sessionId) {
        Path root = resolveSessionRoot(sessionId);
        Path uploads = root.resolve("uploads");
        Path outputs = root.resolve("outputs");
        Path scratch = root.resolve("scratch");
        Path checkpoints = root.resolve("checkpoints");
        try {
            Files.createDirectories(uploads);
            Files.createDirectories(outputs);
            Files.createDirectories(scratch);
            Files.createDirectories(checkpoints);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建会话文件空间: " + root, e);
        }
        return new SessionFileSpace(root, uploads, outputs, scratch, checkpoints);
    }

    public boolean delete(String sessionId) {
        Path root = resolveSessionRoot(sessionId);
        if (!Files.exists(root)) {
            return false;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new DeleteFailure(path, e);
                        }
                    });
            return true;
        } catch (DeleteFailure e) {
            throw new IllegalStateException("删除会话文件空间失败: " + e.path(), e.getCause());
        } catch (IOException e) {
            throw new IllegalStateException("删除会话文件空间失败: " + root, e);
        }
    }

    public Path sessionsRoot() {
        return sessionsRoot;
    }

    private Path resolveSessionRoot(String sessionId) {
        String safeId = validateSessionId(sessionId);
        Path root = sessionsRoot.resolve(safeId).normalize();
        if (!root.startsWith(sessionsRoot) || root.equals(sessionsRoot)) {
            throw new IllegalArgumentException("非法 sessionId: " + sessionId);
        }
        return root;
    }

    private static String validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        String safe = sessionId.strip();
        if (!SAFE_SESSION_ID.matcher(safe).matches()
                || ".".equals(safe)
                || "..".equals(safe)
                || safe.chars().allMatch(ch -> ch == '.')
                || safe.chars().allMatch(ch -> ch == '_')) {
            throw new IllegalArgumentException("非法 sessionId: " + sessionId);
        }
        return safe;
    }

    private static class DeleteFailure extends RuntimeException {
        private final Path path;

        DeleteFailure(Path path, Throwable cause) {
            super(cause);
            this.path = path;
        }

        Path path() {
            return path;
        }
    }

    public record SessionFileSpace(
            Path root,
            Path uploads,
            Path outputs,
            Path scratch,
            Path checkpoints
    ) {
        public Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("root", root.toString());
            map.put("uploads", uploads.toString());
            map.put("outputs", outputs.toString());
            map.put("scratch", scratch.toString());
            map.put("checkpoints", checkpoints.toString());
            return map;
        }
    }
}
