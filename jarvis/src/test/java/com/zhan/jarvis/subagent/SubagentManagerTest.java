package com.zhan.jarvis.subagent;

import com.zhan.jarvis.agent.control.TurnCancellationToken;
import com.zhan.jarvis.channel.SessionKey;
import com.zhan.jarvis.concurrency.ConcurrencyController;
import com.zhan.jarvis.git.WorktreeManager;
import com.zhan.jarvis.llm.AgentLLMProvider;
import com.zhan.jarvis.llm.ChatResponse;
import com.zhan.jarvis.llm.ChatStreamDelta;
import com.zhan.jarvis.llm.Message;
import com.zhan.jarvis.llm.ToolDefinition;
import com.zhan.jarvis.memory.MemoryServiceClient;
import com.zhan.jarvis.server.sse.SseEventHub;
import com.zhan.jarvis.task.TaskManager;
import com.zhan.jarvis.tool.LocalMcpServer;
import com.zhan.jarvis.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentManagerTest {

    @TempDir
    Path tempDir;

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

    @Test
    void hardTimeoutCancelsExecutionAndKeepsTimedOutAsTerminalResult() throws Exception {
        var provider = new BlockingProvider();
        var objectMapper = new ObjectMapper();
        var concurrency = new ConcurrencyController(null);
        var memoryClient = new StubMemoryServiceClient(objectMapper, concurrency);
        var taskManager = new TaskManager(objectMapper, tempDir.toString());
        var manager = new SubagentManager(
                new ToolRegistry(new LocalMcpServer(), List.of()),
                provider,
                memoryClient,
                objectMapper,
                new WorktreeManager(objectMapper, tempDir.toString()),
                taskManager,
                new SseEventHub(),
                tempDir.toString(),
                concurrency,
                Duration.ofMillis(100)
        );

        var handle = manager.spawn(
                "永不主动返回的任务",
                "session-1",
                new SessionKey("http", "default", "session-1"),
                "user-1",
                Map.of(),
                false,
                "",
                false,
                "run-1",
                TurnCancellationToken.none()
        );

        assertThat(handle.started()).isTrue();
        assertThat(provider.started.await(1, TimeUnit.SECONDS)).isTrue();
        try {
            SubagentResult result = manager.waitForCompletion(handle.taskId(), 2_000);

            assertThat(result.status()).isEqualTo("timed_out");
            assertThat(result.error()).contains("最大运行时间");
            assertThat(manager.getResult(handle.taskId()).status()).isEqualTo("timed_out");
            assertThat(taskManager.get(handle.taskId()).orElseThrow().status()).isEqualTo("timed_out");
            assertThat(concurrency.snapshot()).containsEntry("active_subagents", 0);
            assertThat(provider.interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            provider.release.countDown();
        }

        Thread.sleep(50);
        assertThat(manager.getResult(handle.taskId()).status()).isEqualTo("timed_out");
    }

    @Test
    void waitTimeoutDetachesTaskAndPersistsItsLaterTerminalStatus() throws Exception {
        var provider = new BlockingProvider();
        var objectMapper = new ObjectMapper();
        var concurrency = new ConcurrencyController(null);
        var memoryClient = new StubMemoryServiceClient(objectMapper, concurrency);
        var manager = new SubagentManager(
                new ToolRegistry(new LocalMcpServer(), List.of()),
                provider,
                memoryClient,
                objectMapper,
                new WorktreeManager(objectMapper, tempDir.toString()),
                new TaskManager(objectMapper, tempDir.toString()),
                new SseEventHub(),
                tempDir.toString(),
                concurrency,
                Duration.ofMillis(200)
        );

        var handle = manager.spawn(
                "先等待超时、后执行超时的任务",
                "session-2",
                new SessionKey("http", "default", "session-2"),
                "user-1",
                Map.of(),
                false,
                "",
                false,
                "run-2",
                TurnCancellationToken.none()
        );

        assertThat(provider.started.await(1, TimeUnit.SECONDS)).isTrue();
        try {
            SubagentResult waitResult = manager.waitForCompletion(handle.taskId(), 20);

            assertThat(waitResult.status()).isEqualTo("failed");
            assertThat(waitResult.error()).contains("等待超时");
            assertThat(memoryClient.statusPersisted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(manager.getResult(handle.taskId()).status()).isEqualTo("timed_out");
        } finally {
            provider.release.countDown();
        }
    }

    private static final class BlockingProvider implements AgentLLMProvider {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            throw new AssertionError("应调用支持取消信号的 chat 重载");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools,
                                 TurnCancellationToken cancellationToken) {
            started.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    interrupted.countDown();
                    // 模拟不响应线程中断的下游调用，验证迟到结果不会覆盖 timed_out。
                }
            }
            return new ChatResponse("late result", List.of(), "stop", null, null);
        }

        @Override
        public Flux<ChatStreamDelta> streamChat(List<Message> messages, List<ToolDefinition> tools) {
            return Flux.never();
        }
    }

    private static final class StubMemoryServiceClient extends MemoryServiceClient {
        private final CountDownLatch statusPersisted = new CountDownLatch(1);

        private StubMemoryServiceClient(ObjectMapper objectMapper, ConcurrencyController concurrencyController) {
            super(new com.zhan.jarvis.config.JarvisConfig.MemoryServiceConfig("http://localhost"),
                    org.springframework.web.reactive.function.client.WebClient.builder(),
                    objectMapper, concurrencyController);
        }

        @Override
        public String search(String query, int limit) {
            return "";
        }

        @Override
        public void addMessage(String sessionId, String role, String text, Map<String, Object> metadata) {
            statusPersisted.countDown();
        }
    }
}
