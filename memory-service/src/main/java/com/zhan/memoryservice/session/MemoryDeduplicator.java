package com.zhan.memoryservice.session;

import com.zhan.memoryservice.llm.EmbeddingProvider;
import com.zhan.memoryservice.llm.LLMProvider;
import com.zhan.common.llm.PromptManager;
import com.zhan.memoryservice.storage.MetadataStore;
import com.zhan.memoryservice.storage.VectorStore;
import com.zhan.memoryservice.model.ContextEntry;
import com.zhan.memoryservice.model.ContextType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import tools.jackson.databind.ObjectMapper;

/**
 * 记忆去重器，对标 Python MemoryDeduplicator。
 *
 * 两阶段去重:
 *   1. 向量预筛: 用候选记忆 abstract 向量检索同类已有记忆
 *   2. LLM 决策: skip/create/merge
 */
public class MemoryDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(MemoryDeduplicator.class);

    private final VectorStore vectorStore;
    private final MetadataStore metadata;
    private final EmbeddingProvider embedder;
    private final LLMProvider llm;
    private final PromptManager prompts;
    private final ObjectMapper json = new ObjectMapper();

    public MemoryDeduplicator(VectorStore vectorStore, MetadataStore metadata,
                               EmbeddingProvider embedder, LLMProvider llm,
                               PromptManager prompts) {
        this.vectorStore = vectorStore;
        this.metadata = metadata;
        this.embedder = embedder;
        this.llm = llm;
        this.prompts = prompts;
    }

    /**
     * 对一条候选记忆做去重决策。
     *
     * @return DedupResult 包含决策和可选的目标 contentId
     */
    public DedupResult deduplicate(CandidateMemory candidate) {
        // Stage 1: 向量预筛 — 找 top-3 相似的同类已有记忆
        float[] vec = embedder.embed(candidate.abstractText());
        String filter = "context_type == \"memory\"";
        var similarHits = vectorStore.search(vec, filter, 3);

        var similarMemories = new ArrayList<ContextEntry>();
        for (var hit : similarHits) {
            var entry = metadata.getById(hit.contentId()).orElse(null);
            if (entry != null) {
                similarMemories.add(entry);
            }
        }

        // 无相似记忆 → 直接创建
        if (similarMemories.isEmpty()) {
            return new DedupResult("create", "无相似已有记忆", null);
        }

        // Stage 2: LLM 决策
        var existingText = new StringBuilder();
        for (int i = 0; i < similarMemories.size(); i++) {
            var m = similarMemories.get(i);
            existingText.append("[").append(i + 1).append("] ").append(m.contentId()).append("\n");
            existingText.append("摘要: ").append(m.abstractText()).append("\n");
            existingText.append("内容: ").append(m.content().length() > 300
                    ? m.content().substring(0, 300) + "..." : m.content()).append("\n\n");
        }

        var vars = new HashMap<String, String>();
        vars.put("category", candidate.category().value());
        vars.put("candidate_abstract", candidate.abstractText());
        vars.put("candidate_overview", candidate.overview());
        vars.put("candidate_content", candidate.content());
        vars.put("existing_memories", existingText.toString());

        String prompt = prompts.render("session/dedup_decision", vars);
        String response = llm.chat(prompt, "", 0.0);
        String jsonStr = MemoryExtractor.extractJson(response);

        try {
            @SuppressWarnings("unchecked")
            var parsed = json.readValue(jsonStr, Map.class);
            String decision = (String) parsed.getOrDefault("decision", "create");
            String reason = (String) parsed.getOrDefault("reason", "");
            String mergeTarget = (String) parsed.get("merge_target_id");

            // 不支持 merge 的类别，merge 降级为 create
            if ("merge".equals(decision) && !candidate.supportsMerge()) {
                log.debug("类别 {} 不支持合并，降级为 create", candidate.category().value());
                decision = "create";
                mergeTarget = null;
            }

            log.info("去重决策: {} → {} (已有 {} 条相似)", candidate.category().value(), decision, similarMemories.size());
            return new DedupResult(decision, reason, mergeTarget);
        } catch (Exception e) {
            log.warn("去重 LLM 决策解析失败, 默认 create: {}", e.toString());
            return new DedupResult("create", "解析失败默认创建", null);
        }
    }

    public record DedupResult(String decision, String reason, String mergeTargetId) {}
}