package com.zhan.sandboxservice;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 沙箱内实际执行文件和命令操作。
 */
@Component
public class SandboxExecutor {

    private final Path root;
    private final int timeoutSeconds;
    private final int maxOutputChars;

    public SandboxExecutor(SandboxServiceConfig config) {
        this.root = Path.of(valueOrDefault(config.root(), "/workspace")).toAbsolutePath().normalize();
        this.timeoutSeconds = config.timeoutSeconds() > 0 ? config.timeoutSeconds() : 60;
        this.maxOutputChars = config.maxOutputChars() > 0 ? config.maxOutputChars() : 100_000;
    }

    public Map<String, Object> execute(String workspace, String command) throws IOException, InterruptedException {
        if (command == null || command.isBlank()) {
            throw new IOException("缺少 command");
        }
        Path cwd = resolveWorkspace(workspace);
        Files.createDirectories(cwd);

        var pb = new ProcessBuilder("sh", "-c", command);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);

        var process = pb.start();
        var output = new StringBuilder();
        Thread readerThread = Thread.startVirtualThread(() -> readOutput(process, output));

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            readerThread.join(TimeUnit.SECONDS.toMillis(1));
            return Map.of(
                    "success", true,
                    "exitCode", -1,
                    "output", output.toString(),
                    "timedOut", true,
                    "timeoutSeconds", timeoutSeconds,
                    "createdAt", Instant.now().toString()
            );
        }
        readerThread.join(TimeUnit.SECONDS.toMillis(1));
        return Map.of(
                "success", true,
                "exitCode", process.exitValue(),
                "output", output.toString(),
                "timedOut", false,
                "timeoutSeconds", timeoutSeconds,
                "createdAt", Instant.now().toString()
        );
    }

    public Map<String, Object> readFile(String workspace, String path) throws IOException {
        Path file = resolvePath(workspace, path);
        return Map.of(
                "success", true,
                "path", file.toString(),
                "content", Files.readString(file)
        );
    }

    public Map<String, Object> writeFile(String workspace, String path, String content) throws IOException {
        Path file = resolvePath(workspace, path);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content == null ? "" : content);
        return Map.of(
                "success", true,
                "path", file.toString(),
                "contentChars", content == null ? 0 : content.length()
        );
    }

    public Map<String, Object> listDir(String workspace, String path) throws IOException {
        Path dir = resolvePath(workspace, path == null || path.isBlank() ? "." : path);
        try (var stream = Files.list(dir)) {
            List<String> items = stream.sorted().map(Path::toString).toList();
            return Map.of(
                    "success", true,
                    "path", dir.toString(),
                    "items", items
            );
        }
    }

    private Path resolveWorkspace(String workspace) throws IOException {
        String raw = workspace == null || workspace.isBlank() ? root.toString() : workspace;
        Path resolved = Path.of(raw).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("workspace 越界: " + resolved);
        }
        return resolved;
    }

    private Path resolvePath(String workspace, String path) throws IOException {
        Path base = resolveWorkspace(workspace);
        Path candidate = Path.of(path == null || path.isBlank() ? "." : path);
        Path resolved = candidate.isAbsolute()
                ? candidate.toAbsolutePath().normalize()
                : base.resolve(candidate).normalize();
        if (!resolved.startsWith(base)) {
            throw new IOException("路径越界，禁止访问 workspace 之外的文件: " + resolved);
        }
        return resolved;
    }

    private void readOutput(Process process, StringBuilder output) {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendOutput(output, line);
            }
        } catch (IOException ignored) {
            // 进程被终止时流可能关闭，返回已读取内容即可。
        }
    }

    private void appendOutput(StringBuilder output, String line) {
        synchronized (output) {
            if (output.length() < maxOutputChars) {
                output.append(line).append('\n');
            }
        }
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
