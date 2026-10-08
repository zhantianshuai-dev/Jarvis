package com.zhan.jarvis.sandbox;

import com.zhan.jarvis.agent.control.TurnCancellationToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 宿主机直接执行后端。
 * 不提供容器级隔离。除工作目录外的显式路径、路径穿越和嵌套解释器会被拒绝，
 * 以降低直接执行模式的误操作风险；生产环境仍应使用 OS 或 Docker 沙箱。
 */
public class DirectBackend implements SandboxBackend {

    private static final int TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_LENGTH = 100_000;
    private static final Pattern PARENT_TRAVERSAL = Pattern.compile("(?:^|[\\s\\\"'=<>;/|&])\\.\\.(?=$|[\\s\\\"'=<>;/|&])");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("(?:^|[\\s\\\"'=<>])(/[^\\s\\\"'`;&|()<>]*)");
    private static final Pattern NESTED_INTERPRETER = Pattern.compile(
            "(?i)(\\$\\(|`|\\$\\{|\\beval\\b|\\bsource\\b|\\b(?:sh|bash|zsh|dash|fish)\\s+-c\\b|"
                    + "\\b(?:python|python3|node|perl|ruby|php)\\s+-(?:c|e)\\b)");

    @Override
    public CommandResult execute(String command, Path workspaceDir) throws IOException, InterruptedException {
        return execute(command, workspaceDir, TurnCancellationToken.none());
    }

    @Override
    public CommandResult execute(String command, Path workspaceDir, TurnCancellationToken cancellationToken)
            throws IOException, InterruptedException {
        TurnCancellationToken token = cancellationToken == null
                ? TurnCancellationToken.none() : cancellationToken;
        token.throwIfCancellationRequested();
        Path workspace = normalizeWorkspace(workspaceDir);
        Files.createDirectories(workspace);
        validateCommandScope(command, workspace);

        var pb = new ProcessBuilder("sh", "-c", command);
        pb.directory(workspace.toFile());
        pb.redirectErrorStream(true);

        var process = pb.start();
        Thread executionThread = Thread.currentThread();
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

        try (var ignored = token.onCancel(() -> {
            executionThread.interrupt();
            destroyProcessTree(process);
        })) {
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            token.throwIfCancellationRequested();
            if (!finished) {
                destroyProcessTree(process);
                readerThread.join(TimeUnit.SECONDS.toMillis(1));
                return new CommandResult(-1, output.toString(), true, TIMEOUT_SECONDS);
            }
            readerThread.join(TimeUnit.SECONDS.toMillis(1));
            return new CommandResult(process.exitValue(), output.toString(), false, TIMEOUT_SECONDS);
        } catch (InterruptedException e) {
            destroyProcessTree(process);
            if (token.isCancellationRequested()) {
                token.throwIfCancellationRequested();
            }
            throw e;
        } catch (Exception e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("注销命令取消监听器失败", e);
        }
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

    private static void destroyProcessTree(Process process) {
        try {
            process.descendants()
                    .sorted(Comparator.comparingInt(handle -> -depth(handle)))
                    .forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
            // 受限 macOS 环境可能禁止枚举子进程，仍需继续终止主进程。
        } finally {
            process.destroyForcibly();
        }
    }

    private static int depth(ProcessHandle handle) {
        int depth = 0;
        var parent = handle.parent();
        while (parent.isPresent()) {
            depth++;
            parent = parent.get().parent();
        }
        return depth;
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

    /**
     * 直接执行模式无法像容器一样由操作系统强制隔离，因此只接受可静态验证的命令形式。
     * 这会阻止绝对路径和 ../ 逃逸；需要任意 shell 表达能力时必须切换到 OS 或 Docker 沙箱。
     */
    private static void validateCommandScope(String command, Path workspace) throws IOException {
        String source = command == null ? "" : command.strip();
        if (source.isBlank()) {
            throw new IOException("命令不能为空");
        }
        if (PARENT_TRAVERSAL.matcher(source).find()) {
            throw new IOException("直接执行模式拒绝包含 ../ 的路径穿越命令；请使用工作区内相对路径");
        }
        if (NESTED_INTERPRETER.matcher(source).find()) {
            throw new IOException("直接执行模式拒绝嵌套解释器或动态路径表达式；请改用容器沙箱执行该命令");
        }
        Matcher matcher = ABSOLUTE_PATH.matcher(source);
        while (matcher.find()) {
            Path candidate;
            try {
                candidate = Path.of(matcher.group(1)).toAbsolutePath().normalize();
            } catch (Exception e) {
                throw new IOException("命令包含无法验证的绝对路径", e);
            }
            if (!candidate.startsWith(workspace)) {
                throw new IOException("直接执行模式禁止访问工作区外的绝对路径: " + candidate);
            }
        }
    }
}
