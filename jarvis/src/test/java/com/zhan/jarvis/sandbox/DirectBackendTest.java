package com.zhan.jarvis.sandbox;

import com.zhan.jarvis.agent.control.TurnCancellationSource;
import com.zhan.jarvis.agent.control.TurnInterruptedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void cancellationStopsRunningCommand() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        var source = new TurnCancellationSource();
        var execution = CompletableFuture.runAsync(() -> {
            try {
                backend.execute("sleep 10", workspace, source.token());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(150);
        source.cancel("test_interrupted");

        var error = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> execution.get(2, TimeUnit.SECONDS));
        assertThat(rootCause(error)).isInstanceOf(TurnInterruptedException.class);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
