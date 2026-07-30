package com.zhan.memoryservice.service;

import com.zhan.memoryservice.config.MemoryServiceConfig;
import com.zhan.memoryservice.llm.EmbeddingProvider;
import com.zhan.memoryservice.llm.LLMProvider;
import com.zhan.common.llm.PromptManager;
import com.zhan.memoryservice.llm.RerankProvider;
import com.zhan.memoryservice.model.*;
import com.zhan.memoryservice.retrieve.HierarchicalRetriever;
import com.zhan.memoryservice.retrieve.IntentAnalyzer;
import com.zhan.memoryservice.storage.MetadataStore;
import com.zhan.memoryservice.storage.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * 检索编排 — 对标 Python 检索服务。
 *
 * <pre>
 * find(): 纯向量检索，无意图分析 → 层级检索
 * search(): 带意图分析 → 意图分析 → 多个类型化查询并行检索 → 聚合
 * </pre>
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final HierarchicalRetriever retriever;
    private final IntentAnalyzer intentAnalyzer;

    public SearchService(VectorStore vector, MetadataStore metadata,
                         EmbeddingProvider embedder, LLMProvider llm,
                         PromptManager prompts, RerankProvider reranker,
                         MemoryServiceConfig config) {
        this.retriever = new HierarchicalRetriever(vector, metadata, embedder, reranker,
                config.retrieval(), config.rerank());
        this.intentAnalyzer = new IntentAnalyzer(llm, prompts);

        log.info("SearchService 初始化: retrieval={}, hotnessAlpha={}, rerank={}, intent=enabled",
                config.retrieval().globalSearchTopK(), config.retrieval().hotnessAlpha(),
                config.rerank().isAvailable() ? config.rerank().provider() : "off");
    }

    /**
     * 纯语义检索（无意图分析）。
     */
    public FindResult find(String query, int limit) {
        log.info("find: query={}, limit={}", query, limit);

        var tq = new TypedQuery(query, null, "", 3, List.of());
        var result = retriever.retrieve(tq, limit);

        return bucketByType(result.matchedContexts());
    }

    /**
     * 带意图分析的检索。
     * 意图分析器生成多个类型化查询 → 并行检索 → 按分数去重聚合。
     */
    public FindResult search(String query, int limit, String sessionId) {
        log.info("search: query={}, limit={}, sessionId={}", query, limit, sessionId);

        // 意图分析
        QueryPlan plan;
        try {
            plan = intentAnalyzer.analyze("", query, null);
            log.info("意图分析完成: {} 个子查询", plan.queries().size());
        } catch (Exception e) {
            log.warn("意图分析失败, 回退到 find: {}", e.toString());
            return find(query, limit);
        }

        // 对每个类型化查询执行层级检索，按优先级排序。
        var typedQueries = plan.queries().stream()
                .sorted(Comparator.comparingInt(TypedQuery::priority))
                .toList();

        var seenIds = new HashSet<String>();
        var allMatched = new ArrayList<MatchedContext>();

        for (var tq : typedQueries) {
            var result = retriever.retrieve(tq, limit);
            for (var mc : result.matchedContexts()) {
                if (seenIds.add(mc.contentId())) {
                    allMatched.add(mc);
                }
            }
        }

        // 按分数重排序
        allMatched.sort(Comparator.comparingDouble(MatchedContext::score).reversed());

        // 截断到返回数量限制。
        var finalResults = allMatched.size() > limit
                ? allMatched.subList(0, limit) : allMatched;

        log.info("search 完成: {} 个子查询, {} 个最终结果",
                typedQueries.size(), finalResults.size());

        return bucketByType(finalResults);
    }

    /**
     * 按上下文类型分桶为检索结果。
     */
    private FindResult bucketByType(List<MatchedContext> matched) {
        var memories = new ArrayList<MatchedContext>();
        var resources = new ArrayList<MatchedContext>();
        var skills = new ArrayList<MatchedContext>();

        for (var m : matched) {
            switch (m.contextType()) {
                case MEMORY -> memories.add(m);
                case RESOURCE -> resources.add(m);
                case SKILL -> skills.add(m);
            }
        }

        return new FindResult(memories, resources, skills);
    }
}
