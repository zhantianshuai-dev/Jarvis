package com.zhan.memoryservice.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 长期记忆提取输入过滤器。
 * 归档消息仍完整保存在 JSONL 中，但进入长期记忆提取前只保留可学习的对话视图。
 */
public class MemoryExtractionInputFilter {

    private static final int MAX_MESSAGE_CHARS = 4_000;
    private static final int MAX_TOTAL_CHARS = 24_000;
    private static final int MIN_MEANINGFUL_CHARS = 2;

    public FilterResult filter(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return new FilterResult(List.of(), 0, 0, 0);
        }

        var filtered = new ArrayList<Message>();
        int skipped = 0;
        int truncated = 0;
        int totalChars = 0;

        for (var message : messages) {
            if (!shouldKeep(message)) {
                skipped++;
                continue;
            }

            String text = textOf(message);
            if (text.isBlank() || text.strip().length() < MIN_MEANINGFUL_CHARS) {
                skipped++;
                continue;
            }

            String cleaned = sanitizeText(text);
            if (cleaned.isBlank()) {
                skipped++;
                continue;
            }

            boolean messageTruncated = false;
            if (cleaned.length() > MAX_MESSAGE_CHARS) {
                cleaned = cleaned.substring(0, MAX_MESSAGE_CHARS)
                        + "\n...[内容过长，已在长期记忆提取前截断]";
                messageTruncated = true;
            }

            if (totalChars + cleaned.length() > MAX_TOTAL_CHARS) {
                skipped++;
                continue;
            }

            totalChars += cleaned.length();
            if (messageTruncated) {
                truncated++;
            }
            filtered.add(copyWithText(message, cleaned));
        }

        return new FilterResult(List.copyOf(filtered), messages.size(), skipped, truncated);
    }

    private boolean shouldKeep(Message message) {
        if (message == null) {
            return false;
        }
        Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
        if (truthy(metadata.get("trace")) || truthy(metadata.get("hidden"))) {
            return false;
        }

        String role = value(message.role()).toLowerCase(Locale.ROOT);
        if ("user".equals(role)) {
            return true;
        }
        if ("assistant".equals(role)) {
            return truthy(metadata.get("final")) && !truthy(metadata.get("requires_confirmation"));
        }
        return false;
    }

    private String sanitizeText(String text) {
        String cleaned = value(text).strip();
        if (cleaned.isBlank()) {
            return "";
        }
        if (looksLikeToolOrHiddenContext(cleaned)) {
            return "";
        }
        return cleaned;
    }

    private boolean looksLikeToolOrHiddenContext(String text) {
        String trimmed = text.strip();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.startsWith("<system-reminder")
                || lower.startsWith("<context ")
                || lower.startsWith("{\"tool\"")
                || lower.startsWith("{\"deferred_tool_search\"")
                || lower.contains("\"requires_confirmation\"")
                || lower.contains("\"tool_call_id\"")
                || lower.contains("\"raw_result_length\"");
    }

    private Message copyWithText(Message source, String text) {
        var metadata = new LinkedHashMap<String, Object>(source.metadata() == null ? Map.of() : source.metadata());
        metadata.put("memory_extraction_view", true);
        return new Message(
                source.id(),
                source.role(),
                List.of(new Message.Part("text", text, null)),
                source.roleId(),
                metadata,
                Math.max(1, text.length() / 4),
                source.createdAt()
        );
    }

    private String textOf(Message message) {
        var sb = new StringBuilder();
        for (var part : message.parts()) {
            if (part != null && "text".equals(part.type()) && part.text() != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(part.text());
            }
        }
        return sb.toString();
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(value(value));
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record FilterResult(
            List<Message> messages,
            int originalCount,
            int skippedCount,
            int truncatedCount
    ) {}
}
