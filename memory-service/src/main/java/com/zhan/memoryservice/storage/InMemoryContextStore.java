package com.zhan.memoryservice.storage;

import com.zhan.memoryservice.model.ContextEntry;
import com.zhan.memoryservice.model.ContextType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PostgreSQL disabled fallback store.
 * Data is process-local and is lost after restart.
 */
public class InMemoryContextStore implements VectorStore, MetadataStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryContextStore.class);

    private final Map<String, ContextEntry> entries = new ConcurrentHashMap<>();
    private final Map<String, VectorMeta> vectors = new ConcurrentHashMap<>();

    @Override
    public void insert(String contentId, float[] vector, String contextType, String parentId, int level) {
        vectors.put(contentId, new VectorMeta(vector, contextType, parentId, level));
    }

    @Override
    public List<SearchHit> search(float[] vector, String filter, int topK) {
        return vectors.entrySet().stream()
                .map(entry -> toHit(entry.getKey(), entry.getValue(), vector))
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(topK)
                .toList();
    }

    @Override
    public void ensureCollection() {
        log.debug("InMemoryContextStore ensureCollection noop");
    }

    @Override
    public void save(ContextEntry entry) {
        entries.put(entry.contentId(), entry);
    }

    @Override
    public void updateSummaries(String contentId, String abstractText, String overview) {
        entries.computeIfPresent(contentId, (id, entry) -> entry.withSummaries(abstractText, overview));
    }

    @Override
    public Optional<ContextEntry> getById(String contentId) {
        return Optional.ofNullable(entries.get(contentId));
    }

    @Override
    public List<ContextEntry> getByIds(List<String> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return List.of();
        }
        return contentIds.stream()
                .map(entries::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public List<ContextEntry> listByType(ContextType type, int limit, int offset) {
        return entries.values().stream()
                .filter(entry -> entry.contextType() == type)
                .sorted(Comparator.comparing(ContextEntry::updatedAt).reversed())
                .skip(Math.max(offset, 0))
                .limit(limit)
                .toList();
    }

    @Override
    public List<ContextEntry> listChildren(String parentId, int limit) {
        return entries.values().stream()
                .filter(entry -> java.util.Objects.equals(entry.parentId(), parentId))
                .sorted(Comparator.comparing(ContextEntry::updatedAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public void incrementActiveCount(String contentId) {
        entries.computeIfPresent(contentId, (id, entry) -> new ContextEntry(
                entry.contentId(),
                entry.content(),
                entry.abstractText(),
                entry.overview(),
                entry.level(),
                entry.contextType(),
                entry.parentId(),
                entry.activeCount() + 1,
                entry.createdAt(),
                Instant.now(),
                entry.relations()
        ));
    }

    @Override
    public void delete(String contentId) {
        entries.remove(contentId);
        vectors.remove(contentId);
    }

    private SearchHit toHit(String contentId, VectorMeta meta, float[] query) {
        return new SearchHit(contentId, cosine(query, meta.vector), meta.contextType, meta.parentId, meta.level);
    }

    private float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0f;
        }
        int len = Math.min(a.length, b.length);
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0f;
        }
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    private record VectorMeta(float[] vector, String contextType, String parentId, int level) {}
}
