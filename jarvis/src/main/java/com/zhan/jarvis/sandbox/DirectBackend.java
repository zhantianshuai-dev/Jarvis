package com.zhan.jarvis.sandbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 宿主机直接执行后端。
 * 这是最小实现，不提供容器级隔离，但统一限制所有文件操作在 workspace 内。
 */
public class DirectBackend implements SandboxBackend {

    private static final int TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_LENGTH = 100_000;

    @Override
    public CommandResult execute(String command, Path workspaceDir) throws IOException, InterruptedException {
        Path workspace = normalizeWorkspace(workspaceDir);
        Files.createDirectories(workspace);

        var pb = new ProcessBuilder("sh", "-c", command);
        pb.directory(workspace.toFile());
        pb.redirectErrorStream(true);

        var process = pb.start();
        var output = new StringBuilder();
        Thread readerThread = Thread.startVirtualThread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendOutput(output, line);
                }
            } catch (IOException ignored) {
                // 进程被超时终止时流可能关闭；返回已读取到的输出即可。
            }
        });

        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            readerThread.join(TimeUnit.SECONDS.toMillis(1));
            return new CommandResult(-1, output.toString(), true, TIMEOUT_SECONDS);
        }
        readerThread.join(TimeUnit.SECONDS.toMillis(1));
        return new CommandResult(process.exitValue(), output.toString(), false, TIMEOUT_SECONDS);
    }

    @Override
    public String readFile(Path workspaceDir, String path) throws IOException {
        return Files.readString(resolveWorkspacePath(workspaceDir, path));
    }

    @Override
    public Path writeFile(Path workspaceDir, String path, String content) throws IOException {
        Path filePath = resolveWorkspacePath(workspaceDir, path);
        Path parent = filePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(filePath, content);
        return filePath;
    }

    @Override
    public List<Path> listDir(Path workspaceDir, String path) throws IOException {
        try (var stream = Files.list(resolveWorkspacePath(workspaceDir, path))) {
            return stream.sorted().toList();
        }
    }

    private static Path normalizeWorkspace(Path workspaceDir) {
        return workspaceDir.toAbsolutePath().normalize();
    }

    private static void appendOutput(StringBuilder output, String line) {
        synchronized (output) {
            if (output.length() < MAX_OUTPUT_LENGTH) {
                output.append(line).append('\n');
            }
        }
    }

    private static Path resolveWorkspacePath(Path workspaceDir, String path) throws IOException {
        Path workspace = normalizeWorkspace(workspaceDir);//工作目录的绝对路径
        Path candidate = Path.of(path);//LLM传入的路径
        Path resolved = candidate.isAbsolute()
                ? candidate.toAbsolutePath().normalize()
                : workspace.resolve(candidate).normalize();

        if (!resolved.startsWith(workspace)) {
            throw new IOException("路径越界，禁止访问 workspace 之外的文件: " + resolved);
        }
        return resolved;
    }
}
