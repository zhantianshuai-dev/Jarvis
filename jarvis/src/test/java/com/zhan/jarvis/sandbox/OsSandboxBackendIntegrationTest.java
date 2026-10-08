package com.zhan.jarvis.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 真实调用 macOS sandbox-exec 的集成测试。
 * 在已经处于 Seatbelt 内的测试进程中不能嵌套执行，因此仅在显式设置环境变量时运行。
 */
@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "JARVIS_RUN_SEATBELT_TESTS", matches = "true")
class OsSandboxBackendIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void kernelAllowsWorkspaceWritesAndRejectsOutsideWritesFromChildShell() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = tempDir.resolve("outside.txt");
        var backend = new OsSandboxBackend("workspace-write", false, false, 5, 10_000);

        CommandResult allowed = backend.execute("printf 'ok' > inside.txt", workspace);
        CommandResult denied = backend.execute(
                "/bin/sh -c \"printf 'unsafe' > '" + outside + "'\"", workspace);

        assertEquals(0, allowed.exitCode(), allowed.output());
        assertEquals("ok", Files.readString(workspace.resolve("inside.txt")));
        assertNotEquals(0, denied.exitCode(), denied.output());
        assertFalse(Files.exists(outside));
    }

    @Test
    void readOnlyModeRejectsCommandWrites() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        var backend = new OsSandboxBackend("read-only", false, false, 5, 10_000);

        CommandResult denied = backend.execute("printf 'blocked' > blocked.txt", workspace);

        assertNotEquals(0, denied.exitCode(), denied.output());
        assertFalse(Files.exists(workspace.resolve("blocked.txt")));
    }

    @Test
    void networkAccessIsDeniedByDefaultAndCanBeEnabledExplicitly() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        try (var server = new ServerSocket(0)) {
            String probe = "/usr/bin/nc -z 127.0.0.1 " + server.getLocalPort();
            var deniedBackend = new OsSandboxBackend("workspace-write", false, false, 5, 10_000);
            var allowedBackend = new OsSandboxBackend("workspace-write", true, false, 5, 10_000);

            CommandResult denied = deniedBackend.execute(probe, workspace);
            CommandResult allowed = allowedBackend.execute(probe, workspace);

            assertNotEquals(0, denied.exitCode(), denied.output());
            assertEquals(0, allowed.exitCode(), allowed.output());
        }
    }

    @Test
    void canUseHostGitToolchainInsideWorkspace() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        var backend = new OsSandboxBackend("workspace-write", false, true, 5, 10_000);

        CommandResult result = backend.execute("git init -q && git status --short", workspace);

        assertEquals(0, result.exitCode(), result.output());
        assertFalse(result.timedOut());
    }
}
