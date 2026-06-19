package com.zhan.jarvis.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Sandbox 执行后端。
 * 工具只表达读、写、执行等意图，具体在本机、容器还是远程环境执行由后端决定。
 */
public interface SandboxBackend {

    CommandResult execute(String command, Path workspaceDir) throws IOException, InterruptedException;

    String readFile(Path workspaceDir, String path) throws IOException;

    Path writeFile(Path workspaceDir, String path, String content) throws IOException;

    List<Path> listDir(Path workspaceDir, String path) throws IOException;
}
