package com.zhan.jarvis.agent.loop;

import com.zhan.jarvis.session.SessionFileSpaceManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * 将过大的工具结果保存到会话私有文件空间。
 * 模型只保留摘要和虚拟路径，需细节时再用 read_file 分页读取，避免同一份大输出反复进入上下文。
 */
public class ToolResultStore {

    public static final String VIRTUAL_PREFIX = "/mnt/user-data/outputs/.tool-results/";
    private static final int DEFAULT_MAX_LINES = 500;
    private static final int DEFAULT_MAX_CHARS = 12_000;

    private final SessionFileSpaceManager fileSpaceManager;

    public ToolResultStore(SessionFileSpaceManager fileSpaceManager) {
        this.fileSpaceManager = fileSpaceManager;
    }

    public StoredResult save(String sessionId, String toolName, String toolCallId, String content) throws IOException {
        Path directory = resultDirectory(sessionId);
        Files.createDirectories(directory);
        String fileName = safeName(toolName) + "-" + safeName(toolCallId) + "-"
                + UUID.randomUUID() + ".txt";
        Path file = directory.resolve(fileName).normalize();
        if (!file.startsWith(directory)) {
            throw new IllegalArgumentException("非法工具结果文件路径");
        }
        Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
        return new StoredResult(file, VIRTUAL_PREFIX + fileName, content == null ? 0 : content.length());
    }

    public boolean isVirtualPath(String path) {
        return path != null && path.startsWith(VIRTUAL_PREFIX);
    }

    public String read(String sessionId, String virtualPath, int startLine, int endLine, int maxChars)
            throws IOException {
        Path directory = resultDirectory(sessionId);
        Path file = resolveVirtualPath(directory, virtualPath);
        if (!Files.isRegularFile(file)) {
            throw new IOException("工具结果不存在或已被清理");
        }

        int from = Math.max(1, startLine);
        int to = endLine >= from ? endLine : from + DEFAULT_MAX_LINES - 1;
        int limit = maxChars > 0 ? Math.min(maxChars, DEFAULT_MAX_CHARS) : DEFAULT_MAX_CHARS;
        var output = new StringBuilder();
        boolean truncated = false;
        int lineNumber = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber < from) {
                    continue;
                }
                if (lineNumber > to) {
                    truncated = true;
                    break;
                }
                if (output.length() + line.length() + 1 > limit) {
                    truncated = true;
                    break;
                }
                output.append(line).append('\n');
            }
        }
        if (output.isEmpty() && lineNumber < from) {
            return "工具结果没有第 " + from + " 行。";
        }
        if (truncated) {
            output.append("\n[结果已截断；请缩小行范围后继续读取]\n");
        }
        return output.toString();
    }

    private Path resultDirectory(String sessionId) {
        return fileSpaceManager.ensure(sessionId).outputs().resolve(".tool-results").normalize();
    }

    private Path resolveVirtualPath(Path directory, String virtualPath) {
        if (!isVirtualPath(virtualPath)) {
            throw new IllegalArgumentException("不是工具结果虚拟路径");
        }
        String fileName = virtualPath.substring(VIRTUAL_PREFIX.length());
        if (fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("非法工具结果虚拟路径");
        }
        Path file = directory.resolve(fileName).normalize();
        if (!file.startsWith(directory)) {
            throw new IllegalArgumentException("非法工具结果虚拟路径");
        }
        return file;
    }

    private String safeName(String value) {
        String normalized = value == null ? "tool" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "_");
        return normalized.isBlank() ? "tool" : normalized.substring(0, Math.min(normalized.length(), 48));
    }

    public record StoredResult(Path path, String virtualPath, int chars) {}
}
