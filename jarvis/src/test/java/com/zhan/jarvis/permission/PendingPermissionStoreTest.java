package com.zhan.jarvis.permission;

import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.session.SessionFileSpaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingPermissionStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void wrongUserCannotConsumeConfirmation() {
        var store = store();
        store.put(permission("confirm_1", "run_1", "user_1"));

        assertThatThrownBy(() -> store.take("confirm_1", "user_2"))
                .isInstanceOf(PendingPermissionStore.PermissionOwnerMismatchException.class);
        assertThat(store.take("confirm_1", "user_1")).isPresent();
    }

    @Test
    void revokeRunRemovesMemoryAndPersistedPermission() {
        var firstStore = store();
        firstStore.put(permission("confirm_1", "run_1", "user_1"));

        assertThat(firstStore.revokeRun("run_1")).isEqualTo(1);
        assertThat(firstStore.take("confirm_1", "user_1")).isEmpty();
        assertThat(store().take("confirm_1", "user_1")).isEmpty();
    }

    private PendingPermissionStore store() {
        return new PendingPermissionStore(new SessionFileSpaceManager(tempDir.toString()), new ObjectMapper());
    }

    private PendingToolPermission permission(String confirmId, String runId, String userId) {
        return new PendingToolPermission(
                confirmId,
                "git",
                Map.of("action", "push", "remote", "origin", "branch", "main"),
                "session_1",
                new SessionKey("http", "default", "session_1"),
                tempDir.toString(),
                userId,
                Map.of("run_id", runId),
                "执行 git push origin main",
                Instant.now().plusSeconds(60)
        );
    }
}
