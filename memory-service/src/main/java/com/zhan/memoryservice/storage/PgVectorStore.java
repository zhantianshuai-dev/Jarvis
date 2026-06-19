package com.zhan.memoryservice.storage;

import com.pgvector.PGvector;
import com.zhan.memoryservice.exception.MemoryServiceException.StorageException;
import com.zhan.memoryservice.model.ContextEntry;
import com.zhan.memoryservice.model.ContextType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.*;

/**
 * PostgreSQL + pgvector 统一存储 — 向量 + 元数据二合一。
 * <p>
 * 同时实现 VectorStore 和 MetadataStore，共享同一张 context_entry 表。
 * pgvector HNSW 索引替代 Milvus 的 ANN 检索。
 */
@Component
@ConditionalOnProperty(prefix = "memory-service.postgres", name = "enabled", havingValue = "true")
public class PgVectorStore implements VectorStore, MetadataStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    private final JdbcClient jdbc;
    private final int dimension;

    public PgVectorStore(JdbcClient jdbc, com.zhan.memoryservice.config.MemoryServiceConfig config) {
        this.jdbc = jdbc;
        this.dimension = config.embedding().dimension();
        initTable();
        log.info("PgVectorStore 初始化完成: dimension={}", dimension);
    }

    // ==================== 建表 ====================

    private void initTable() {
        jdbc.sql("CREATE EXTENSION IF NOT EXISTS vector").update();

        String ddl = """
            CREATE TABLE IF NOT EXISTS context_entry (
                content_id    VARCHAR(128) PRIMARY KEY,
                content       TEXT,
                abstract_text VARCHAR(500),
                overview      TEXT,
                level         INT NOT NULL DEFAULT 2,
                context_type  VARCHAR(32) NOT NULL,
                parent_id     VARCHAR(128),
                active_count  INT NOT NULL DEFAULT 0,
                embedding     vector(%d),
                created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """.formatted(dimension);
        jdbc.sql(ddl).update();

        // HNSW 索引（幂等创建，已存在则跳过）
        try {
            jdbc.sql("""
                CREATE INDEX IF NOT EXISTS idx_entry_embedding
                ON context_entry USING hnsw (embedding vector_cosine_ops)
                """).update();
        } catch (Exception e) {
            log.debug("HNSW 索引可能存在: {}", e.getMessage());
        }
    }

    // ==================== VectorStore ====================

    @Override
    public void insert(String contentId, float[] vector, String contextType, String parentId, int level) {
        jdbc.sql("""
            INSERT INTO context_entry (content_id, embedding, context_type, parent_id, level)
            VALUES (:id, :vec, :type, :pid, :level)
            ON CONFLICT (content_id) DO UPDATE SET
                embedding    = EXCLUDED.embedding,
                context_type = EXCLUDED.context_type,
                parent_id    = EXCLUDED.parent_id,
                level        = EXCLUDED.level,
                updated_at   = NOW()
            """)
            .param("id", contentId)
            .param("vec", new PGvector(vector))
            .param("type", contextType)
            .param("pid", parentId)
            .param("level", level)
            .update();
    }

    @Override
    public List<SearchHit> search(float[] vector, String filter, int topK) {
        String where = convertFilter(filter);
        var sql = """
            SELECT content_id, context_type, parent_id, level,
                   1.0 - (embedding <=> :vec::vector) AS score
            FROM context_entry
            WHERE embedding IS NOT NULL
            """;
        if (!where.isBlank()) sql += " AND " + where;
        sql += " ORDER BY embedding <=> :vec::vector LIMIT :limit";

        return jdbc.sql(sql)
                .param("vec", new PGvector(vector))
                .param("limit", topK)
                .query((rs, rowNum) -> new SearchHit(
                        rs.getString("content_id"),
                        rs.getFloat("score"),
                        rs.getString("context_type"),
                        rs.getString("parent_id"),
                        rs.getInt("level")
                ))
                .list();
    }

    @Override
    public void ensureCollection() {
        initTable();
    }

    /**
     * 将 Milvus 表达式转为 PostgreSQL SQL。
     * 处理: == → =, && → AND, "..." → '...'
     */
    private String convertFilter(String filter) {
        if (filter == null || filter.isBlank()) return "";
        return filter
                .replace("==", "=")     // Milvus 等号
                .replace("&&", "AND")    // Milvus 逻辑与
                .replace("\"", "'");     // Milvus 字符串引号 → SQL 单引号
    }

    // ==================== MetadataStore ====================

    @Override
    public void save(ContextEntry entry) {
        try {
            jdbc.sql("""
                INSERT INTO context_entry (content_id, content, abstract_text, overview,
                    level, context_type, parent_id, active_count, created_at, updated_at)
                VALUES (:id, :content, :abstract, :overview,
                    :level, :type, :parentId, :activeCount, :createdAt, :updatedAt)
                ON CONFLICT (content_id) DO UPDATE SET
                    content       = EXCLUDED.content,
                    abstract_text = EXCLUDED.abstract_text,
                    overview      = EXCLUDED.overview,
                    level         = EXCLUDED.level,
                    context_type  = EXCLUDED.context_type,
                    parent_id     = EXCLUDED.parent_id,
                    active_count  = EXCLUDED.active_count,
                    updated_at    = EXCLUDED.updated_at
                """)
                .param("id", entry.contentId())
                .param("content", entry.content())
                .param("abstract", entry.abstractText())
                .param("overview", entry.overview())
                .param("level", entry.level())
                .param("type", entry.contextType().value())
                .param("parentId", entry.parentId())
                .param("activeCount", entry.activeCount())
                .param("createdAt", Timestamp.from(entry.createdAt()))
                .param("updatedAt", Timestamp.from(entry.updatedAt()))
                .update();
        } catch (Exception e) {
            log.error("写入元数据失败: contentId={}, 原因: {}", entry.contentId(), e.toString());
            throw new StorageException("写入元数据失败: " + entry.contentId(), e);
        }
    }

    @Override
    public void updateSummaries(String contentId, String abstractText, String overview) {
        jdbc.sql("""
            UPDATE context_entry
            SET abstract_text = :abstract, overview = :overview, updated_at = NOW()
            WHERE content_id = :id
            """)
            .param("abstract", abstractText)
            .param("overview", overview)
            .param("id", contentId)
            .update();
    }

    @Override
    public Optional<ContextEntry> getById(String contentId) {
        var list = jdbc.sql(selectSQL() + " WHERE content_id = :id")
                .param("id", contentId)
                .query(this::mapRow)
                .list();
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    @Override
    public List<ContextEntry> getByIds(List<String> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) return List.of();
        var escaped = contentIds.stream()
                .map(id -> "'" + id.replace("'", "''") + "'")
                .toList();
        return jdbc.sql(selectSQL() + " WHERE content_id IN (" + String.join(",", escaped) + ")")
                .query(this::mapRow)
                .list();
    }

    @Override
    public List<ContextEntry> listByType(ContextType type, int limit, int offset) {
        return jdbc.sql(selectSQL() + " WHERE context_type = :type ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
                .param("type", type.value())
                .param("limit", limit)
                .param("offset", offset)
                .query(this::mapRow)
                .list();
    }

    @Override
    public List<ContextEntry> listChildren(String parentId, int limit) {
        return jdbc.sql(selectSQL() + " WHERE parent_id = :pid ORDER BY updated_at DESC LIMIT :limit")
                .param("pid", parentId)
                .param("limit", limit)
                .query(this::mapRow)
                .list();
    }

    @Override
    public void incrementActiveCount(String contentId) {
        jdbc.sql("UPDATE context_entry SET active_count = active_count + 1 WHERE content_id = :id")
                .param("id", contentId)
                .update();
    }

    @Override
    public void delete(String contentId) {
        jdbc.sql("DELETE FROM context_entry WHERE content_id = :id")
                .param("id", contentId)
                .update();
    }

    // ==================== 内部 ====================

    private static String selectSQL() {
        return """
            SELECT content_id, content, abstract_text, overview, level, context_type,
                   parent_id, active_count, created_at, updated_at
            FROM context_entry
            """;
    }

    private ContextEntry mapRow(ResultSet rs, int rowNum) {
        try {
            return new ContextEntry(
                rs.getString("content_id"),
                rs.getString("content"),
                rs.getString("abstract_text"),
                rs.getString("overview"),
                rs.getInt("level"),
                ContextType.valueOf(rs.getString("context_type").toUpperCase()),
                rs.getString("parent_id"),
                rs.getInt("active_count"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                List.of()
            );
        } catch (Exception e) {
            throw new StorageException("映射数据库行失败", e);
        }
    }
}
