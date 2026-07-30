package com.zhan.jarvis.agent.loop;

import com.zhan.jarvis.config.JarvisConfig;
import com.zhan.jarvis.llm.Message;
import com.zhan.jarvis.llm.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeContextBudgeterTest {

    private final RuntimeContextBudgeter budgeter = new RuntimeContextBudgeter();

    @Test
    void compressesRuntimeMessagesWhenBudgetExceeded() {
        var messages = new ArrayList<Message>();
        messages.add(Message.system("核心系统提示词"));
        messages.add(Message.system("""
                <context kind="working_memory">
                %s
                </context>
                """.formatted("长期上下文".repeat(100))));
        for (int i = 0; i < 8; i++) {
            messages.add(Message.user("旧用户消息 " + i + " " + "内容".repeat(60)));
            messages.add(Message.assistant("旧助手回复 " + i + " " + "内容".repeat(60)));
        }
        for (int i = 0; i < 5; i++) {
            messages.add(Message.tool("call_" + i, "工具结果 " + i + " " + "结果".repeat(120)));
        }
        messages.add(Message.user("当前任务"));

        var config = new JarvisConfig.AgentConfig.ContextBudgetConfig(
                true,
                300,
                80,
                80,
                6
        );
        var tools = List.of(new ToolDefinition("read_file", "读取文件", Map.of("type", "object")));

        var result = budgeter.apply(messages, tools, config);

        assertThat(result.changed()).isTrue();
        assertThat(result.compressedMessages()).isGreaterThan(0);
        assertThat(result.removedMessages()).isGreaterThan(0);
        assertThat(messages).extracting(Message::content)
                .anyMatch(content -> content != null && content.contains("runtime_context_budget"))
                .anyMatch(content -> content != null && content.contains("旧工具结果过长"))
                .anyMatch(content -> content != null && content.contains("上下文块过长"))
                .contains("当前任务");
    }

    @Test
    void leavesMessagesUntouchedWhenDisabled() {
        var messages = new ArrayList<>(List.of(
                Message.system("系统提示词"),
                Message.user("你好")
        ));
        var config = new JarvisConfig.AgentConfig.ContextBudgetConfig(
                false,
                1,
                1,
                1,
                1
        );

        var result = budgeter.apply(messages, List.of(), config);

        assertThat(result.changed()).isFalse();
        assertThat(messages).hasSize(2);
    }
}
