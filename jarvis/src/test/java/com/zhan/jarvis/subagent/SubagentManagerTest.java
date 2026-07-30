package com.zhan.jarvis.subagent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentManagerTest {

    @Test
    void filteredSubagentMetadataKeepsOnlySafeContext() {
        var filtered = SubagentManager.filteredSubagentMetadata(Map.of(
                "workspace", "project",
                "mode", "agent",
                "current_message", "主 Agent 当前完整问题",
                "confirm_id", "confirm_1",
                "tool_name", "git",
                "open_id", "ou_xxx",
                "allowed_tool_groups", List.of("git", "exec")
        ));

        assertThat(filtered)
                .containsEntry("workspace", "project")
                .containsEntry("mode", "agent")
                .containsEntry("open_id", "ou_xxx")
                .containsEntry("subagent_isolated", true)
                .doesNotContainKeys("current_message", "confirm_id", "tool_name");
        var allowedGroups = ((List<?>) filtered.get("allowed_tool_groups"))
                .stream()
                .map(Objects::toString)
                .toList();
        assertThat(allowedGroups).containsExactly("git", "exec");
    }
}
