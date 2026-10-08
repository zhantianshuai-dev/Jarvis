package com.zhan.jarvis.sandbox;

import com.zhan.jarvis.agent.control.TurnCancellationToken;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 沙箱执行后端。
 * 工具只表达读、写、执行等意图，具体在本机、容器还是远程环境执行由后端决定。
 */
public interface SandboxBackend {

    CommandResult execute(String command, Path workspaceDir) throws IOException, InterruptedException;

    default CommandResult execute(String command, Path workspaceDir, TurnCancellationToken cancellationToken)
            throws IOException, InterruptedException {
        TurnCancellationToken token = cancellationToken == null
                ? TurnCancellationToken.none() : cancellationToken;
        token.throwIfCancellationRequested();
        CommandResult result = execute(command, workspaceDir);
        token.throwIfCancellationRequested();
        return result;
    }

    String readFile(Path workspaceDir, String path) throws IOException;

    Path writeFile(Path workspaceDir, String path, String content) throws IOException;

    List<Path> listDir(Path workspaceDir, String path) throws IOException;
}
