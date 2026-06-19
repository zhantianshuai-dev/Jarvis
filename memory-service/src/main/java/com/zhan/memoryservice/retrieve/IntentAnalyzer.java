package com.zhan.memoryservice.retrieve;

import com.zhan.memoryservice.llm.LLMProvider;
import com.zhan.common.llm.PromptManager;
import com.zhan.memoryservice.model.ContextType;
import com.zhan.memoryservice.model.QueryPlan;
import com.zhan.memoryservice.model.TypedQuery;
import com.zhan.memoryservice.tracker.TokenUsageTracker;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 意图分析器，对标 Python IntentAnalyzer。
 * 调用 LLM 分析会话上下文，生成 QueryPlan（多个 TypedQuery）。
 */
public class IntentAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(IntentAnalyzer.class);

    private static final int MAX_COMPRESSION_SUMMARY_CHARS = 30000;
    private static final String INTENT_ANALYSIS_KEY = "retrieval/intent_analysis";

    private final LLMProvider llm;
    private final PromptManager prompts;
    private final ObjectMapper json = new ObjectMapper();

    public IntentAnalyzer(LLMProvider llm, PromptManager prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    /**
     * 分析会话上下文，生成查询计划。
     *
     * @param compressionSummary 会话压缩摘要（可为空）
     * @param currentMessage     当前用户消息
     * @param contextType        限定类型（可为 null）
     * @return QueryPlan
     */
    public QueryPlan analyze(String compressionSummary, String currentMessage, ContextType contextType) {
        var vars = new HashMap<String, String>();
        vars.put("compression_summary", truncate(compressionSummary, MAX_COMPRESSION_SUMMARY_CHARS));
        vars.put("recent_messages", "");
        vars.put("current_message", currentMessage != null ? currentMessage : "");
        vars.put("context_type", contextType != null ? contextType.value() : "");
        vars.put("target_abstract", "");

        String prompt = prompts.render(INTENT_ANALYSIS_KEY, vars);
        log.debug("意图分析 prompt 长度: {} 字符", prompt.length());

        String response = llm.chat(prompt, "", 0.0);
        log.debug("意图分析 LLM 响应: {}", response.length() > 300 ? response.substring(0, 300) + "..." : response);

        // 解析 JSON（LLM 返回可能包裹在 ```json ... ``` 中）
        String jsonStr = extractJson(response);

        try {
            @SuppressWarnings("unchecked")
            var parsed = json.readValue(jsonStr, Map.class);
            String reasoning = (String) parsed.getOrDefault("reasoning", "");

            @SuppressWarnings("unchecked")
            var rawQueries = (List<Map<String, Object>>) parsed.getOrDefault("queries", List.of());

            var queries = new ArrayList<TypedQuery>();
            for (var q : rawQueries) {
                String query = (String) q.getOrDefault("query", "");
                String ctStr = (String) q.getOrDefault("context_type", "resource");
                ContextType ct;
                try {
                    ct = ContextType.valueOf(ctStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    ct = ContextType.RESOURCE;
                }
                String intent = (String) q.getOrDefault("intent", "");
                int priority = q.get("priority") instanceof Number n ? n.intValue() : 3;

                queries.add(new TypedQuery(query, ct, intent, priority, List.of()));
                log.info("  意图查询 [{}]: type={}, priority={}, query=\"{}\"", queries.size(), ct.value(), priority, query);
            }
            log.debug("意图分析 reasoning: {}", reasoning.length() > 200 ? reasoning.substring(0, 200) + "..." : reasoning);

            return new QueryPlan(queries, summarizeContext(compressionSummary, currentMessage), reasoning);

        } catch (Exception e) {
            log.error("解析意图分析响应失败", e);
            throw new RuntimeException("意图分析失败: " + e.getMessage(), e);
        }
    }

    /** 从 LLM 响应中提取 JSON（可能被 ```json ... ``` 包裹） */
    static String extractJson(String response) {
        if (response == null) return "{}";
        String trimmed = response.trim();
        int jsonStart = trimmed.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = trimmed.indexOf('\n', jsonStart) + 1;
            int jsonEnd = trimmed.indexOf("```", contentStart);
            if (jsonEnd > contentStart) {
                return trimmed.substring(contentStart, jsonEnd).trim();
            }
        }
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }
        return "{}";
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars - 15) + "\n...(已截断)";
    }

    private static String summarizeContext(String compressionSummary, String currentMessage) {
        var parts = new ArrayList<String>();
        if (compressionSummary != null && !compressionSummary.isBlank()) {
            parts.add("会话摘要: " + compressionSummary);
        }
        if (currentMessage != null && !currentMessage.isBlank()) {
            parts.add("当前消息: " + (currentMessage.length() > 100 ? currentMessage.substring(0, 100) : currentMessage));
        }
        return parts.isEmpty() ? "无上下文" : String.join(" | ", parts);
    }
}
