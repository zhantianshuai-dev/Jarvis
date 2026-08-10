package com.zhan.jarvis.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirectBackendTest {

    @TempDir
    Path tempDir;

    private final DirectBackend backend = new DirectBackend();

    @Test
    void allowsRelativePathInsideWorkspace() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));

        CommandResult result = backend.execute("printf 'ok' > safe.txt", workspace);

        assertEquals(0, result.exitCode());
        assertEquals("ok", Files.readString(workspace.resolve("safe.txt")));
    }

    @Test
    void rejectsParentTraversalBeforeStartingShell() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = tempDir.resolve("outside.txt");

        assertThrows(java.io.IOException.class,
                () -> backend.execute("printf 'unsafe' > ../outside.txt", workspace));

        assertFalse(Files.exists(outside));
    }

    @Test
    void rejectsAbsolutePathOutsideWorkspace() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = tempDir.resolve("outside.txt");

        assertThrows(java.io.IOException.class,
                () -> backend.execute("printf 'unsafe' > " + outside, workspace));

        assertFalse(Files.exists(outside));
    }
}
