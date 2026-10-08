package com.zhan.jarvis.sandbox;

import com.zhan.jarvis.agent.control.TurnCancellationToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 操作系统原生沙箱后端。
 *
 * <p>当前实现支持 macOS Seatbelt。每条命令都由 {@code /usr/bin/sandbox-exec} 启动，
 * 文件系统与网络权限由动态 SBPL 策略强制执行；不支持的平台会直接失败，不会降级为宿主机直跑。</p>
 */
public class OsSandboxBackend implements SandboxBackend {

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_MAX_OUTPUT_CHARS = 100_000;

    private final String mode;
    private final boolean networkAccess;
    private final boolean allowTempWrite;
    private final int timeoutSeconds;
    private final int maxOutputChars;
    private final Path tempDir;

    public OsSandboxBackend(String mode, boolean networkAccess, boolean allowTempWrite,
                            int timeoutSeconds, int maxOutputChars) {
        requireMacOsSeatbelt();
        this.mode = SeatbeltPolicy.normalizeMode(mode);
        this.networkAccess = networkAccess;
        this.allowTempWrite = allowTempWrite;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        this.maxOutputChars = maxOutputChars > 0 ? maxOutputChars : DEFAULT_MAX_OUTPUT_CHARS;
        this.tempDir = resolveTempDir();
    }

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
        if (command == null || command.isBlank()) {
            throw new IOException("命令不能为空");
        }
        Path workspace = normalizeWorkspace(workspaceDir);
        String policy = SeatbeltPolicy.build(mode, networkAccess, allowTempWrite);
        var processBuilder = new ProcessBuilder(SeatbeltPolicy.command(policy, workspace, tempDir, command));
        processBuilder.directory(workspace.toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().put("JARVIS_SANDBOX", "seatbelt");
        if (!networkAccess) {
            processBuilder.environment().put("JARVIS_SANDBOX_NETWORK_DISABLED", "1");
        }

        Process process = processBuilder.start();
        Thread executionThread = Thread.currentThread();
        var output = new StringBuilder();
        Thread readerThread = Thread.startVirtualThread(() -> readOutput(process, output));

        try (var ignored = token.onCancel(() -> {
            executionThread.interrupt();
            destroyProcessTree(process);
        })) {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            token.throwIfCancellationRequested();
            if (!finished) {
                destroyProcessTree(process);
                readerThread.join(TimeUnit.SECONDS.toMillis(1));
                return new CommandResult(-1, output.toString(), true, timeoutSeconds);
            }
            readerThread.join(TimeUnit.SECONDS.toMillis(1));
            return new CommandResult(process.exitValue(), output.toString(), false, timeoutSeconds);
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
        return Files.readString(resolveWorkspacePath(workspaceDir, path, true));
    }

    @Override
    public Path writeFile(Path workspaceDir, String path, String content) throws IOException {
        if (SeatbeltPolicy.MODE_READ_ONLY.equals(mode)) {
            throw new IOException("OS sandbox 当前为 read-only，禁止写入文件");
        }
        Path file = resolveWorkspacePath(workspaceDir, path, false);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
            ensureRealPathInsideWorkspace(normalizeWorkspace(workspaceDir), parent);
        }
        Files.writeString(file, content == null ? "" : content);
        return file;
    }

    @Override
    public List<Path> listDir(Path workspaceDir, String path) throws IOException {
        Path directory = resolveWorkspacePath(workspaceDir, path, true);
        try (var stream = Files.list(directory)) {
            return stream.sorted().toList();
        }
    }

    private Path normalizeWorkspace(Path workspaceDir) throws IOException {
        if (workspaceDir == null) {
            throw new IOException("workspace 不能为空");
        }
        Path logical = normalizeTopLevelAlias(workspaceDir.toAbsolutePath().normalize());
        if (!Files.isDirectory(logical, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("OS sandbox workspace 不存在或不是目录: " + logical);
        }
        rejectNestedSymlinks(logical);
        return logical.toRealPath();
    }

    private Path resolveWorkspacePath(Path workspaceDir, String path, boolean mustExist) throws IOException {
        Path workspace = normalizeWorkspace(workspaceDir);
        Path candidate = Path.of(path == null || path.isBlank() ? "." : path);
        Path resolved = candidate.isAbsolute()
                ? normalizeTopLevelAlias(candidate.toAbsolutePath().normalize())
                : workspace.resolve(candidate).normalize();
        if (!resolved.startsWith(workspace)) {
            throw new IOException("路径越界，禁止访问 workspace 之外的文件: " + resolved);
        }

        if (mustExist || Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            ensureRealPathInsideWorkspace(workspace, resolved);
        } else {
            Path existingAncestor = nearestExistingAncestor(resolved);
            ensureRealPathInsideWorkspace(workspace, existingAncestor);
        }
        return resolved;
    }

    private static Path nearestExistingAncestor(Path path) throws IOException {
        Path current = path;
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IOException("无法确定目标路径的有效父目录: " + path);
        }
        return current;
    }

    /**
     * macOS 的 /tmp、/var 等顶层路径可能是系统符号链接。仅解析这一层系统别名，
     * 后续用户可控路径仍由 rejectNestedSymlinks 拒绝。
     */
    private static Path normalizeTopLevelAlias(Path path) throws IOException {
        Path root = path.getRoot();
        if (root == null || path.getNameCount() == 0) {
            return path;
        }
        Path topLevel = root.resolve(path.getName(0));
        if (!Files.isSymbolicLink(topLevel)) {
            return path;
        }
        Path canonicalTopLevel = topLevel.toRealPath();
        return canonicalTopLevel.resolve(topLevel.relativize(path)).normalize();
    }

    private static void rejectNestedSymlinks(Path path) throws IOException {
        Path current = path.getRoot();
        if (current == null) {
            throw new IOException("OS sandbox 路径必须是绝对路径: " + path);
        }
        for (Path component : path) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("OS sandbox 不接受包含符号链接的 workspace: " + path);
            }
        }
    }

    private static void ensureRealPathInsideWorkspace(Path workspace, Path path) throws IOException {
        Path real = path.toRealPath();
        if (!real.startsWith(workspace)) {
            throw new IOException("路径通过符号链接越界，禁止访问: " + path);
        }
    }

    private void readOutput(Process process, StringBuilder output) {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    int remaining = maxOutputChars - output.length();
                    if (remaining <= 0) {
                        continue;
                    }
                    if (line.length() + 1 <= remaining) {
                        output.append(line).append('\n');
                    } else {
                        output.append(line, 0, remaining);
                    }
                }
            }
        } catch (IOException ignored) {
            // 超时终止进程树时输出流可能关闭，保留已经读取到的内容。
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

    private static Path resolveTempDir() {
        try {
            return Path.of(System.getProperty("java.io.tmpdir", "/tmp"))
                    .toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            return Path.of("/private/tmp");
        }
    }

    private static void requireMacOsSeatbelt() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("mac")) {
            throw new IllegalStateException("OS sandbox 当前仅支持 macOS Seatbelt；其他平台请使用 docker/http 后端");
        }
        Path executable = Path.of(SeatbeltPolicy.SEATBELT_EXECUTABLE);
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new IllegalStateException("未找到可执行的 macOS Seatbelt: s" + executable);
        }
    }
}
