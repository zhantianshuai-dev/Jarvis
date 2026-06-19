package com.zhan.jarvis.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Sandbox 统一入口。
 * 后续切换 DockerBackend 或 RemoteBackend 时，工具层无需改动。
 */
public class SandboxManager {

    private final SandboxBackend backend;

    public SandboxManager(SandboxBackend backend) {
        this.backend = backend;
    }

    public CommandResult execute(String command, String workspaceDir) throws IOException, InterruptedException {
        return backend.execute(command, Path.of(workspaceDir));
    }

    public String readFile(String workspaceDir, String path) throws IOException {
        return backend.readFile(Path.of(workspaceDir), path);
    }

    public Path writeFile(String workspaceDir, String path, String content) throws IOException {
        return backend.writeFile(Path.of(workspaceDir), path, content);
    }

    public List<Path> listDir(String workspaceDir, String path) throws IOException {
        return backend.listDir(Path.of(workspaceDir), path);
    }
}
