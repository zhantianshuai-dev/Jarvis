package com.zhan.memoryservice.session;

import com.zhan.memoryservice.llm.LLMProvider;
import com.zhan.common.llm.PromptManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import tools.jackson.databind.ObjectMapper;

/**
 * 记忆提取器，对标 Python MemoryExtractor。
 * 调用 LLM 从对话中提取 8 类结构化记忆。
 */
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    private final LLMProvider llm;
    private final PromptManager prompts;
    private final ObjectMapper json = new ObjectMapper();

    public MemoryExtractor(LLMProvider llm, PromptManager prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    /**
     * 从消息列表中提取候选记忆。
     */
    public List<CandidateMemory> extract(List<Message> messages) {
        if (messages.isEmpty()) return List.of();

        // 格式化为文本
        var sb = new StringBuilder();
        for (var msg : messages) {
            sb.append("[").append(msg.role()).append("]: ");
            for (var part : msg.parts()) {
                if ("text".equals(part.type()) && part.text() != null) {
                    sb.append(part.text());
                }
            }
            sb.append("\n");
        }

        String formatted = sb.toString();
        String prompt = prompts.render("session/memory_extraction",
                Map.of("messages", formatted));
        log.debug("记忆提取 prompt 长度: {} 字符", prompt.length());

        String response = llm.chat(prompt, "", 0.0);
        String jsonStr = extractJson(response);

        try {
            @SuppressWarnings("unchecked")
            var parsed = json.readValue(jsonStr, Map.class);
            String reasoning = (String) parsed.getOrDefault("reasoning", "");
            log.debug("记忆提取 reasoning: {}", reasoning);

            @SuppressWarnings("unchecked")
            var rawMemories = (List<Map<String, Object>>) parsed.getOrDefault("memories", List.of());

            var result = new ArrayList<CandidateMemory>();
            for (var m : rawMemories) {
                String catStr = (String) m.getOrDefault("category", "entities");
                MemoryCategory category;
                try {
                    category = MemoryCategory.fromValue(catStr);
                } catch (IllegalArgumentException e) {
                    log.warn("未知记忆类别: {}, 跳过", catStr);
                    continue;
                }

                String ab = (String) m.getOrDefault("abstract", "");
                String ov = (String) m.getOrDefault("overview", "");
                String content = (String) m.getOrDefault("content", "");

                if (ab.isBlank() && content.isBlank()) continue;

                result.add(new CandidateMemory(category, ab, ov, content));
                log.info("提取记忆: category={}, abstract={}", category.value(),
                        ab.length() > 80 ? ab.substring(0, 80) + "..." : ab);
            }

            return result;
        } catch (Exception e) {
            log.error("记忆提取解析失败", e);
            return List.of();
        }
    }

    /** 从 LLM 响应中提取 JSON */
    static String extractJson(String response) {
        if (response == null) return "{}";
        String trimmed = response.trim();
        int jsonStart = trimmed.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = trimmed.indexOf('\n', jsonStart) + 1;
            int jsonEnd = trimmed.indexOf("```", contentStart);
            if (jsonEnd > contentStart) return trimmed.substring(contentStart, jsonEnd).trim();
        }
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) return trimmed.substring(braceStart, braceEnd + 1);
        return "{}";
    }
}