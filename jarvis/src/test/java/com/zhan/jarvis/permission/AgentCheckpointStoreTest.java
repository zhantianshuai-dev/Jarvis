package com.zhan.jarvis.permission;

import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.llm.Message;
import com.zhan.jarvis.session.SessionFileSpaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCheckpointStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void takeByConfirmIdConsumesCheckpointOnce() {
        var store = store();
        var checkpoint = checkpoint("checkpoint_1", "confirm_1", Instant.now().plusSeconds(60));

        store.put(checkpoint);

        assertThat(store.take("confirm_1")).contains(checkpoint);
        assertThat(store.take("confirm_1")).isEmpty();
    }

    @Test
    void expiredCheckpointIsNotReturned() {
        var store = store();

        store.put(checkpoint("checkpoint_1", "confirm_1", Instant.now().minusSeconds(1)));

        assertThat(store.take("confirm_1")).isEmpty();
        assertThat(store.listSession("session_1")).isEmpty();
    }

    @Test
    void listSessionReturnsCurrentCheckpoints() {
        var store = store();
        var first = checkpoint("checkpoint_1", "confirm_1", Instant.now().plusSeconds(60));
        var second = checkpoint("checkpoint_2", "confirm_2", Instant.now().plusSeconds(60));

        store.put(first);
        store.put(second);

        assertThat(store.listSession("session_1"))
                .extracting(AgentCheckpoint::checkpointId)
                .containsExactlyInAnyOrder("checkpoint_1", "checkpoint_2");
    }

    @Test
    void checkpointCanBeRecoveredFromFile() {
        var firstStore = store();
        var checkpoint = checkpoint("checkpoint_1", "confirm_1", Instant.now().plusSeconds(60));
        firstStore.put(checkpoint);

        var secondStore = store();

        assertThat(secondStore.take("confirm_1"))
                .map(AgentCheckpoint::checkpointId)
                .contains("checkpoint_1");
        assertThat(secondStore.take("confirm_1")).isEmpty();
    }

    private AgentCheckpointStore store() {
        return new AgentCheckpointStore(new SessionFileSpaceManager(tempDir.toString()), new ObjectMapper());
    }

    private AgentCheckpoint checkpoint(String checkpointId, String confirmId, Instant expiresAt) {
        return new AgentCheckpoint(
                checkpointId,
                "run_1",
                confirmId,
                "call_1",
                new SessionKey("http", "default", "session_1"),
                "session_1",
                "user_1",
                List.of(Message.user("执行 git push")),
                2,
                Map.of("mode", "agent"),
                Map.of("stream", false),
                Map.of("prompt_tokens", 10, "completion_tokens", 2, "total_tokens", 12, "context_tokens", 10),
                "agent",
                Set.of("git"),
                Map.of("tool_name", "git"),
                Instant.now(),
                expiresAt
        );
    }
}
