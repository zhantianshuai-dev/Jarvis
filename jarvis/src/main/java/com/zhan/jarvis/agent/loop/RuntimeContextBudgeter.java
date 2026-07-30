package com.zhan.jarvis.agent.loop;

import com.zhan.jarvis.config.JarvisConfig;
import com.zhan.jarvis.llm.Message;
import com.zhan.jarvis.llm.ToolDefinition;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AgentLoop 运行态上下文预算器。
 * 只压缩当前本轮传给 LLM 的消息列表，不修改 JSONL 持久化历史。
 */
public class RuntimeContextBudgeter {

    private static final int DEFAULT_MAX_INPUT_TOKENS = 16_000;
    private static final int DEFAULT_MAX_TOOL_RESULT_CHARS = 1_200;
    private static final int DEFAULT_MAX_CONTEXT_BLOCK_CHARS = 4_000;
    private static final int DEFAULT_KEEP_RECENT_MESSAGES = 12;
    private static final int RECENT_TOOL_MESSAGES_TO_KEEP = 4;

    public BudgetResult apply(List<Message> messages, List<ToolDefinition> tools,
                              JarvisConfig.AgentConfig.ContextBudgetConfig config) {
        if (messages == null || messages.isEmpty() || !enabled(config)) {
            return new BudgetResult(false, estimate(messages, tools), estimate(messages, tools), 0, 0);
        }

        int before = estimate(messages, tools);
        int maxTokens = maxInputTokens(config);
        if (before <= maxTokens) {
            return new BudgetResult(false, before, before, 0, 0);
        }

        int compressed = compressLargeSystemContexts(messages, maxContextBlockChars(config));
        compressed += compressOldToolResults(messages, maxToolResultChars(config));
        int removed = 0;

        int afterCompression = estimate(messages, tools);
        if (afterCompression > maxTokens) {
            removed = removeOldOrdinaryMessages(messages, keepRecentMessages(config));
        }

        int after = estimate(messages, tools);
        if (compressed > 0 || removed > 0) {
            insertBudgetSummary(messages, before, after, compressed, removed);
        }

        return new BudgetResult(compressed > 0 || removed > 0, before, estimate(messages, tools), compressed, removed);
    }

    public int estimate(List<Message> messages, List<ToolDefinition> tools) {
        int total = 0;
        if (messages != null) {
            for (var message : messages) {
                total += estimate(message);
            }
        }
        if (tools != null) {
            for (var tool : tools) {
                total += estimate(tool);
            }
        }
        return Math.max(total, 0);
    }

    private int estimate(Message message) {
        if (message == null) {
            return 0;
        }
        int chars = 24;
        chars += length(message.role());
        chars += length(message.content());
        chars += length(message.toolCallId());
        chars += length(message.reasoningContent());
        if (message.toolCalls() != null) {
            for (var toolCall : message.toolCalls()) {
                chars += 48;
                chars += length(toolCall.id());
                chars += length(toolCall.name());
                chars += length(toolCall.arguments());
            }
        }
        return Math.max(8, chars / 4);
    }

    private int estimate(ToolDefinition tool) {
        if (tool == null) {
            return 0;
        }
        int chars = 80;
        chars += length(tool.name());
        chars += length(tool.description());
        chars += length(tool.group());
        chars += length(tool.source());
        chars += length(tool.schemaWeight());
        chars += tool.inputSchema() != null ? tool.inputSchema().toString().length() : 0;
        return Math.max(16, chars / 4);
    }

    private int compressLargeSystemContexts(List<Message> messages, int maxChars) {
        int changed = 0;
        for (int i = 0; i < messages.size(); i++) {
            var message = messages.get(i);
            String content = message.content();
            if (!"system".equals(message.role()) || content == null || content.length() <= maxChars) {
                continue;
            }
            if (!content.startsWith("<context kind=")) {
                continue;
            }
            messages.set(i, Message.system(truncatedContextBlock(content, maxChars)));
            changed++;
        }
        return changed;
    }

    private int compressOldToolResults(List<Message> messages, int maxChars) {
        int changed = 0;
        int seenFromEnd = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            var message = messages.get(i);
            if (!"tool".equals(message.role())) {
                continue;
            }
            seenFromEnd++;
            String content = message.content();
            if (seenFromEnd <= RECENT_TOOL_MESSAGES_TO_KEEP || content == null || content.length() <= maxChars) {
                continue;
            }
            messages.set(i, Message.tool(message.toolCallId(), compressedToolResult(content, maxChars)));
            changed++;
        }
        return changed;
    }

    private int removeOldOrdinaryMessages(List<Message> messages, int keepRecentMessages) {
        if (messages.size() <= keepRecentMessages) {
            return 0;
        }

        Set<Integer> keep = new HashSet<>();
        int nonSystemSeen = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            var message = messages.get(i);
            if ("system".equals(message.role())) {
                keep.add(i);
                continue;
            }
            if (nonSystemSeen < keepRecentMessages) {
                keep.add(i);
                nonSystemSeen++;
            }
            if ("tool".equals(message.role()) || message.toolCalls() != null) {
                keep.add(i);
            }
        }

        int removed = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (keep.contains(i)) {
                continue;
            }
            var message = messages.get(i);
            if (isOrdinaryDialogue(message)) {
                messages.remove(i);
                removed++;
            }
        }
        return removed;
    }

    private boolean isOrdinaryDialogue(Message message) {
        if (message == null) {
            return false;
        }
        if ("tool".equals(message.role()) || "system".equals(message.role())) {
            return false;
        }
        return message.toolCalls() == null || message.toolCalls().isEmpty();
    }

    private void insertBudgetSummary(List<Message> messages, int before, int after, int compressed, int removed) {
        String summary = """
                <context kind="runtime_context_budget">
                本轮 LLM 请求前已压缩运行态上下文。
                原估算输入 token: %d
                压缩后估算输入 token: %d
                压缩消息数: %d
                移除旧对话消息数: %d
                被压缩或移除的内容仍保存在会话持久化历史中；如需细节，请按具体文件、工具结果或历史范围重新读取。
                </context>
                """.formatted(before, after, compressed, removed).strip();

        int insertAt = Math.min(2, messages.size());
        messages.add(insertAt, Message.system(summary));
    }

    private String truncatedContextBlock(String content, int maxChars) {
        String firstLine = firstLine(content);
        String closing = closingTag(content);
        int previewStart = content.indexOf('\n');
        String body = previewStart >= 0 ? content.substring(previewStart + 1) : content;
        return firstLine + "\n"
                + body.substring(0, Math.min(maxChars, body.length()))
                + "\n...[上下文块过长，运行态已截断]\n"
                + closing;
    }

    private String compressedToolResult(String content, int maxChars) {
        return """
                {"runtime_compressed":true,"summary":"旧工具结果过长，已在运行态上下文中压缩。完整结果仍保存在会话历史中。","preview":%s,"original_chars":%d}
                """.formatted(jsonString(content.substring(0, Math.min(maxChars, content.length()))), content.length()).strip();
    }

    private String jsonString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

    private String firstLine(String content) {
        int idx = content.indexOf('\n');
        return idx > 0 ? content.substring(0, idx) : content;
    }

    private String closingTag(String content) {
        int idx = content.lastIndexOf("</context>");
        return idx >= 0 ? "</context>" : "";
    }

    private boolean enabled(JarvisConfig.AgentConfig.ContextBudgetConfig config) {
        return config == null || config.enabled();
    }

    private int maxInputTokens(JarvisConfig.AgentConfig.ContextBudgetConfig config) {
        return config != null && config.maxInputTokens() > 0
                ? config.maxInputTokens()
                : DEFAULT_MAX_INPUT_TOKENS;
    }

    private int maxToolResultChars(JarvisConfig.AgentConfig.ContextBudgetConfig config) {
        return config != null && config.maxToolResultChars() > 0
                ? config.maxToolResultChars()
                : DEFAULT_MAX_TOOL_RESULT_CHARS;
    }

    private int maxContextBlockChars(JarvisConfig.AgentConfig.ContextBudgetConfig config) {
        return config != null && config.maxContextBlockChars() > 0
                ? config.maxContextBlockChars()
                : DEFAULT_MAX_CONTEXT_BLOCK_CHARS;
    }

    private int keepRecentMessages(JarvisConfig.AgentConfig.ContextBudgetConfig config) {
        return config != null && config.keepRecentMessages() > 0
                ? config.keepRecentMessages()
                : DEFAULT_KEEP_RECENT_MESSAGES;
    }

    private int length(String value) {
        return value != null ? value.length() : 0;
    }

    public record BudgetResult(
            boolean changed,
            int beforeTokens,
            int afterTokens,
            int compressedMessages,
            int removedMessages
    ) {}
}
