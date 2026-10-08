package com.zhan.jarvis.agent.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActiveRunRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void interruptCancelsTokenAndPersistsOwner() {
        var registry = new ActiveRunRegistry(tempDir, new ObjectMapper());
        registry.prepare("run_1", "session_1", "user_1");
        var token = registry.start("run_1", "session_1", "user_1", null);

        var snapshot = registry.interrupt("run_1", "user_1", "user_interrupted");

        assertThat(token.isCancellationRequested()).isTrue();
        assertThat(snapshot.status()).isEqualTo(RunStatus.CANCELLING);
        assertThat(snapshot.ownerUserId()).isEqualTo("user_1");
        assertThat(tempDir.resolve("runs/index.json")).exists();
    }

    @Test
    void rejectsInterruptFromAnotherUser() {
        var registry = new ActiveRunRegistry(tempDir, new ObjectMapper());
        registry.prepare("run_1", "session_1", "user_1");

        assertThatThrownBy(() -> registry.interrupt("run_1", "user_2", "user_interrupted"))
                .isInstanceOf(ActiveRunRegistry.RunAccessDeniedException.class);
    }

    @Test
    void reconcilesRunningRunAsInterruptedAfterRestart() {
        var first = new ActiveRunRegistry(tempDir, new ObjectMapper());
        first.prepare("run_1", "session_1", "user_1");
        first.start("run_1", "session_1", "user_1", null);

        var restored = new ActiveRunRegistry(tempDir, new ObjectMapper());

        assertThat(restored.get("run_1")).get()
                .extracting(RunSnapshot::status, RunSnapshot::reason)
                .containsExactly(RunStatus.INTERRUPTED, "server_restarted");
    }
}
