package com.zhan.memoryservice.retrieve;

import com.zhan.memoryservice.config.MemoryServiceConfig;
import com.zhan.memoryservice.llm.EmbeddingProvider;
import com.zhan.memoryservice.llm.RerankProvider;
import com.zhan.memoryservice.model.*;
import com.zhan.memoryservice.storage.MetadataStore;
import com.zhan.memoryservice.storage.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 层级检索器，对标 Python HierarchicalRetriever。
 *
 * <pre>
 * 检索链路:
 *   1. 向量化查询
 *   2. 全局向量检索 — 定位入口目录 (level == 0)
 *   3. 合并起始点 — 全局命中目录 + 根目录
 *   4. 优先队列 BFS 递归检索 + 分数传播
 *   5. 收敛检查 — 连续 N 轮 topK 不变则退出
 *   6. Rerank 重排序（可选）
 *   7. Hotness 冷热度混合
 * </pre>
 */
public class HierarchicalRetriever {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalRetriever.class);

    private final VectorStore vectorStore;
    private final MetadataStore metadata;
    private final EmbeddingProvider embedder;
    private final RerankProvider reranker;
    private final HotnessScorer hotness;

    private final int maxConvergenceRounds;
    private final int globalSearchTopK;
    private final double scorePropagationAlpha;
    private final double hotnessAlpha;
    private final double threshold;

    public HierarchicalRetriever(VectorStore vectorStore, MetadataStore metadata,
                                 EmbeddingProvider embedder, RerankProvider reranker,
                                 MemoryServiceConfig.RetrievalConfig retrieval,
                                 MemoryServiceConfig.RerankConfig rerankConfig) {
        this.vectorStore = vectorStore;
        this.metadata = metadata;
        this.embedder = embedder;
        this.reranker = reranker;
        this.hotness = new HotnessScorer(retrieval.defaultHalfLifeDays());
        this.maxConvergenceRounds = retrieval.maxConvergenceRounds();
        this.globalSearchTopK = retrieval.globalSearchTopK();
        this.scorePropagationAlpha = retrieval.scorePropagationAlpha();
        this.hotnessAlpha = retrieval.hotnessAlpha();
        this.threshold = rerankConfig.threshold();
    }

    /**
     * 执行层级检索。
     */
    public QueryResult retrieve(TypedQuery typedQuery, int limit) {
        String query = typedQuery.query();
        log.info("层级检索: query={}, type={}, limit={}", query, typedQuery.contextType(), limit);

        // Step 1: 向量化
        float[] queryVector = embedder.embed(query);

        // Step 2: 全局向量检索 — 找入口目录 (level == 0)
        var globalHits = globalVectorSearch(queryVector, typedQuery.contextType(), limit);
        log.info("全局检索: {} 个入口目录", globalHits.size());

        // Step 3: 合并起始点 — 全局检索结果全部进候选池 + 目录队列
        var candidates = new LinkedHashMap<String, Candidate>();
        var dirQueue = new PriorityQueue<DirEntry>();
        var startingSeen = new HashSet<String>();

        for (var hit : globalHits) {
            double s = Double.isFinite(hit.score()) ? hit.score() : 0.0;
            // 全部进候选池
            candidates.put(hit.contentId(), new Candidate(hit, s));
            // level != 2 的条目也作为起始目录（后续递归探索其子条目）
            if (hit.level() != 2 && startingSeen.add(hit.contentId())) {
                dirQueue.offer(new DirEntry(hit.contentId(), s));
            }
        }
        // 根目录兜底 — parent_id 为空的条目如果没有被全局检索覆盖，补零分加入
        for (var rootId : getRootIds(typedQuery.contextType())) {
            if (startingSeen.add(rootId)) {
                dirQueue.offer(new DirEntry(rootId, 0.0));
            }
        }

        log.info("起始目录: {} 个, 初始候选: {} 个", dirQueue.size(), candidates.size());

        // Step 4: BFS 递归检索
        int searchLimit = Math.max(limit * 2, 20);
        recursiveSearch(queryVector, dirQueue, candidates, searchLimit, limit, typedQuery.contextType());

        var ranked = new ArrayList<>(candidates.values());
        ranked.sort(Comparator.comparingDouble(Candidate::score).reversed());

        // Step 5: Rerank
        if (reranker != null && reranker.isAvailable() && !ranked.isEmpty()) {
            rerankCandidates(query, ranked);
            ranked.sort(Comparator.comparingDouble(Candidate::rerankOrVectorScore).reversed());
        }

        // Step 6: Hotness 混合 → MatchedContext
        var matched = convertToMatchedContexts(ranked);
        var finalResults = matched.size() > limit ? matched.subList(0, limit) : matched;

        log.info("层级检索完成: {} 个最终结果", finalResults.size());
        return new QueryResult(typedQuery, finalResults, List.of());  //原始查询，查询结果，搜索过的目录
    }

    // ---- 全局检索 ----

    private List<VectorStore.SearchHit> globalVectorSearch(float[] vector, ContextType ct, int limit) {
        var filter = new StringBuilder("level == 0");
        if (ct != null) {
            filter.append(" && context_type == \"").append(ct.value()).append("\"");
        }
        return vectorStore.search(vector, filter.toString(), Math.max(limit, globalSearchTopK));
    }

    /** 根目录 ID — 父目录为空的条目 */
    private List<String> getRootIds(ContextType ct) {
        // 从 H2 查指定类型且 parent_id 为 null 的条目
        if (ct != null) {
            return metadata.listByType(ct, 20, 0).stream()
                    .filter(e -> e.parentId() == null || e.parentId().isBlank())
                    .map(ContextEntry::contentId)
                    .toList();
        }
        // 不限类型时，查所有根条目
        var roots = new ArrayList<String>();
        for (var type : ContextType.values()) {
            metadata.listByType(type, 10, 0).stream()
                    .filter(e -> e.parentId() == null || e.parentId().isBlank())
                    .map(ContextEntry::contentId)
                    .forEach(roots::add);
        }
        return roots;
    }

    // ---- 递归检索 ----

    private void recursiveSearch(float[] queryVector, PriorityQueue<DirEntry> dirQueue,
                                  LinkedHashMap<String, Candidate> candidates,
                                  int searchLimit, int limit, ContextType ct) {
        var visited = new HashSet<String>();
        Set<String> prevTopKIds = Set.of();
        int convergenceRounds = 0;
        int prevPoolSize = 0;
        int stagnantRounds = 0;

        while (!dirQueue.isEmpty()) {
            var dir = dirQueue.poll();
            if (!visited.add(dir.contentId)) continue;

            log.debug("进入目录: contentId={}, score={}", dir.contentId, String.format("%.4f", dir.score));

            // 检索子条目
            String childFilter = "parent_id == \"" + dir.contentId + "\"";
            if (ct != null) {
                childFilter += " && context_type == \"" + ct.value() + "\"";
            }
            var children = vectorStore.search(queryVector, childFilter, searchLimit);

            if (children.isEmpty()) continue;

            double currentScore = dir.score;
            for (var child : children) {
                double childScore = Double.isFinite(child.score()) ? child.score() : 0.0;
                // 分数传播: finalScore = alpha * childScore + (1-alpha) * parentScore
                double finalScore = currentScore > 0
                        ? scorePropagationAlpha * childScore + (1 - scorePropagationAlpha) * currentScore
                        : childScore;

                if (finalScore <= threshold) continue;

                // 去重，保留最高分
                var prev = candidates.get(child.contentId());
                if (prev == null || finalScore > prev.score) {
                    candidates.put(child.contentId(), new Candidate(child, finalScore));
                }

                // 子目录加入队列继续递归
                if (!visited.contains(child.contentId()) && child.level() != 2) {
                    dirQueue.offer(new DirEntry(child.contentId(), finalScore));
                }
            }

            // 收敛检查
            var topK = candidates.values().stream()
                    .sorted(Comparator.comparingDouble(Candidate::score).reversed())
                    .limit(limit)
                    .toList();
            var topKIds = new HashSet<String>();
            for (var c : topK) topKIds.add(c.hit.contentId());

            if (topKIds.equals(prevTopKIds) && topKIds.size() >= limit) {
                convergenceRounds++;
                if (convergenceRounds >= maxConvergenceRounds) {
                    log.debug("检索收敛: topK 连续 {} 轮不变", convergenceRounds);
                    break;
                }
            } else if (candidates.size() == prevPoolSize) {
                stagnantRounds++;
                if (stagnantRounds >= maxConvergenceRounds) {
                    log.debug("检索停滞: 候选池连续 {} 轮不变", stagnantRounds);
                    break;
                }
            } else {
                convergenceRounds = 0;
                stagnantRounds = 0;
                prevTopKIds = topKIds;
                prevPoolSize = candidates.size();
            }
        }
    }

    // ---- Rerank ----

    private void rerankCandidates(String query, List<Candidate> candidates) {
        var ids = candidates.stream().map(c -> c.hit.contentId()).toList();
        var idToAbstract = new HashMap<String, String>();
        for (var e : metadata.getByIds(ids)) {
            String ab = e.abstractText();
            if (ab != null && !ab.isBlank()) {
                idToAbstract.put(e.contentId(), ab);
            }
        }

        var docs = new ArrayList<String>();
        var fallbackScores = new ArrayList<Float>();
        for (var c : candidates) {
            docs.add(idToAbstract.getOrDefault(c.hit.contentId(), ""));
            fallbackScores.add((float) c.score);
        }

        try {
            var rerankScores = reranker.rerank(query, docs);
            if (rerankScores.size() != docs.size()) {
                log.debug("Rerank 结果数量不匹配: {} vs {}", rerankScores.size(), docs.size());
                return;
            }
            for (int i = 0; i < candidates.size(); i++) {
                candidates.get(i).rerankScore = (double) rerankScores.get(i);
            }
            log.debug("Rerank 完成: {} 个候选", candidates.size());
        } catch (Exception e) {
            log.warn("Rerank 失败，回退到向量分数: {}", e.toString());
        }
    }

    // ---- Hotness + 结果转换 ----

    private List<MatchedContext> convertToMatchedContexts(List<Candidate> candidates) {
        var ids = candidates.stream().map(c -> c.hit.contentId()).toList();
        var idToEntry = new HashMap<String, ContextEntry>();
        for (var e : metadata.getByIds(ids)) {
            idToEntry.put(e.contentId(), e);
            metadata.incrementActiveCount(e.contentId());
        }

        var results = new ArrayList<MatchedContext>();
        for (var c : candidates) {
            var entry = idToEntry.get(c.hit.contentId());
            double semanticScore = c.rerankOrVectorScore();
            if (!Double.isFinite(semanticScore)) semanticScore = 0.0;

            double finalScore;
            if (hotnessAlpha > 0 && entry != null) {
                double hScore = hotness.score(entry.activeCount(), entry.updatedAt());
                finalScore = (1 - hotnessAlpha) * semanticScore + hotnessAlpha * hScore;
            } else {
                finalScore = semanticScore;
            }
            if (!Double.isFinite(finalScore)) finalScore = 0.0;

            String ab = entry != null && entry.abstractText() != null ? entry.abstractText() : "";
            String ov = entry != null && entry.overview() != null ? entry.overview() : "";
            ContextType ct = entry != null ? entry.contextType() : ContextType.RESOURCE;

            results.add(new MatchedContext(c.hit.contentId(), ct, ab, ov, finalScore));
        }

        results.sort(Comparator.comparingDouble(MatchedContext::score).reversed());
        return results;
    }

    // ---- 内部类型 ----

    /** 优先队列条目: 目录 + 分数，按分数降序 */
    private record DirEntry(String contentId, double score) implements Comparable<DirEntry> {
        @Override public int compareTo(DirEntry o) {
            return Double.compare(o.score, this.score); // 降序
        }
    }

    /** 检索候选 */
    static class Candidate {
        final VectorStore.SearchHit hit;
        double score;        // 向量语义分数（含父目录传播）
        Double rerankScore;  // rerank 分数（可选）

        Candidate(VectorStore.SearchHit hit, double score) {
            this.hit = hit;
            this.score = score;
        }

        double score() { return score; }

        double rerankOrVectorScore() {
            return rerankScore != null ? rerankScore : score;
        }
    }
}
