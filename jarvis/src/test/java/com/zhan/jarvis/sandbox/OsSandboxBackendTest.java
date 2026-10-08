package com.zhan.jarvis.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledOnOs(OS.MAC)
class OsSandboxBackendTest {

    @TempDir
    Path tempDir;

    @Test
    void fileOperationsStayInsideWorkspace() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        var backend = new OsSandboxBackend("workspace-write", false, true, 5, 1_000);

        Path written = backend.writeFile(workspace, "src/example.txt", "ok");

        assertEquals("ok", backend.readFile(workspace, "src/example.txt"));
        assertEquals(workspace.toRealPath().resolve("src/example.txt"), written);
        assertThrows(IOException.class, () -> backend.writeFile(workspace, "../outside.txt", "unsafe"));
        assertFalse(Files.exists(tempDir.resolve("outside.txt")));
    }

    @Test
    void readOnlyModeRejectsFileWrites() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        var backend = new OsSandboxBackend("read-only", false, false, 5, 1_000);

        assertThrows(IOException.class, () -> backend.writeFile(workspace, "blocked.txt", "blocked"));
    }

    @Test
    void rejectsSymlinkThatEscapesWorkspace() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.createSymbolicLink(workspace.resolve("escape"), outside);
        var backend = new OsSandboxBackend("workspace-write", false, true, 5, 1_000);

        assertThrows(IOException.class, () -> backend.writeFile(workspace, "escape/outside.txt", "unsafe"));
        assertFalse(Files.exists(outside.resolve("outside.txt")));
    }
}
