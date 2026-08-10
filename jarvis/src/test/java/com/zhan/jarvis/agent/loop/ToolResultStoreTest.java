package com.zhan.jarvis.agent.loop;

import com.zhan.jarvis.session.SessionFileSpaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolResultStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savesLargeResultUnderCurrentSessionAndReadsRequestedLines() throws Exception {
        var store = new ToolResultStore(new SessionFileSpaceManager(tempDir.toString()));
        String content = "line-1\nline-2\nline-3\nline-4\n";

        var stored = store.save("web_session_1", "exec", "call_1", content);

        assertThat(stored.virtualPath()).startsWith(ToolResultStore.VIRTUAL_PREFIX);
        assertThat(stored.path()).exists();
        assertThat(store.read("web_session_1", stored.virtualPath(), 2, 3, 1_000))
                .contains("line-2", "line-3")
                .doesNotContain("line-1", "line-4");
    }

    @Test
    void rejectsResultPathOutsideCurrentSession() {
        var store = new ToolResultStore(new SessionFileSpaceManager(tempDir.toString()));

        assertThatThrownBy(() -> store.read("web_session_1",
                ToolResultStore.VIRTUAL_PREFIX + "../other-session/result.txt", 1, 10, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
