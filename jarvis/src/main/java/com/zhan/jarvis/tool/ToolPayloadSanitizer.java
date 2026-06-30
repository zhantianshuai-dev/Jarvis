package com.zhan.jarvis.tool;

import com.zhan.jarvis.llm.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将工具调用中的大块或敏感字段替换为可追溯摘要。
 * 原始参数仍用于工具执行；清洗后的参数只进入 LLM 历史、SSE 事件和持久化 metadata。
 */
public class ToolPayloadSanitizer {

    private static final Logger log = LoggerFactory.getLogger(ToolPayloadSanitizer.class);
    private final ObjectMapper objectMapper;

    public ToolPayloadSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ToolCall> sanitizeToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        var sanitized = new ArrayList<ToolCall>(toolCalls.size());
        for (var toolCall : toolCalls) {
            sanitized.add(sanitizeToolCall(toolCall));
        }
        return List.copyOf(sanitized);
    }

    public ToolCall sanitizeToolCall(ToolCall toolCall) {
        if (toolCall == null) {
            return null;
        }
        return new ToolCall(toolCall.id(), toolCall.name(),
                sanitizeArguments(toolCall.name(), toolCall.arguments()));
    }

    public String sanitizeArguments(String toolName, String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return arguments == null ? "{}" : arguments;
        }
        if (!requiresArgumentSanitizing(toolName)) {
            return arguments;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(arguments, Map.class);
            return objectMapper.writeValueAsString(sanitizeArgumentMap(toolName, args));
        } catch (Exception e) {
            log.debug("工具参数清洗失败: tool={}, {}", toolName, e.getMessage());
            return "{\"arguments_omitted\":true,\"sanitize_error\":\"无法解析原始工具参数\"}";
        }
    }

    public Map<String, Object> sanitizeArgumentMap(String toolName, Map<String, Object> arguments) {
        var sanitized = new LinkedHashMap<String, Object>();
        if (arguments != null) {
            sanitized.putAll(arguments);
        }
        if ("write_file".equals(toolName)) {
            omitTextField(sanitized, "content");
        } else if ("edit_file".equals(toolName)) {
            omitTextField(sanitized, "old_text");
            omitTextField(sanitized, "new_text");
        }
        return sanitized;
    }

    public Map<String, Object> toolCallMetadata(ToolCall toolCall) {
        var item = new LinkedHashMap<String, Object>();
        item.put("id", toolCall.id());
        item.put("name", toolCall.name());
        item.put("arguments", sanitizeArguments(toolCall.name(), toolCall.arguments()));
        if (requiresArgumentSanitizing(toolCall.name())) {
            item.put("arguments_sanitized", true);
        }
        return item;
    }

    public String loggableArguments(String toolName, String arguments) {
        String sanitized = sanitizeArguments(toolName, arguments);
        if (sanitized == null || sanitized.length() <= 500) {
            return sanitized;
        }
        return sanitized.substring(0, 500) + "...";
    }

    public boolean requiresArgumentSanitizing(String toolName) {
        return "write_file".equals(toolName) || "edit_file".equals(toolName);
    }

    private void omitTextField(Map<String, Object> args, String field) {
        //这里对content_omitted=true作判断，已经处理的字段不会再处理
        if (Boolean.parseBoolean(String.valueOf(args.get(field + "_omitted")))) {
            return;
        }
        Object value = args.get(field);
        if (!(value instanceof String text)) {
            return;
        }
        args.put(field, "[omitted after tool execution; read the target file if exact content is needed]");
        args.put(field + "_omitted", true);
        args.put(field + "_chars", text.length());
        args.put(field + "_sha256", sha256(text));
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
