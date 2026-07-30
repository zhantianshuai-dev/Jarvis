package com.zhan.jarvis.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SessionFileSpaceManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void ensureCreatesSessionFileSpaceLayout() {
        var manager = new SessionFileSpaceManager(tempDir.toString());

        var space = manager.ensure("web_1");

        assertThat(Files.isDirectory(space.root())).isTrue();
        assertThat(Files.isDirectory(space.uploads())).isTrue();
        assertThat(Files.isDirectory(space.outputs())).isTrue();
        assertThat(Files.isDirectory(space.scratch())).isTrue();
        assertThat(Files.isDirectory(space.checkpoints())).isTrue();
        assertThat(space.root()).startsWith(manager.sessionsRoot());
    }

    @Test
    void ensureSanitizesUnsafeSessionId() {
        var manager = new SessionFileSpaceManager(tempDir.toString());

        var space = manager.ensure("../unsafe/session");

        assertThat(space.root()).startsWith(manager.sessionsRoot());
        assertThat(space.root().getFileName().toString()).isEqualTo(".._unsafe_session");
    }

    @Test
    void deleteRemovesWholeSessionFileSpace() throws Exception {
        var manager = new SessionFileSpaceManager(tempDir.toString());
        var space = manager.ensure("web_1");
        Files.writeString(space.outputs().resolve("report.md"), "hello");

        assertThat(manager.delete("web_1")).isTrue();

        assertThat(Files.exists(space.root())).isFalse();
    }
}
