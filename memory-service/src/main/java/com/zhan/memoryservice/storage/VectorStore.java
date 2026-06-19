package com.zhan.memoryservice.storage;

import java.util.List;

/**
 * 向量存储接口 — 抽象向量数据库操作。
 */
public interface VectorStore {

    /** 插入一批向量及其对应的 content_id 和标量元数据 */
    void insert(String contentId, float[] vector, String contextType, String parentId, int level);

    /**
     * ANN 检索，返回 content_id + 相似度分数 + 元数据。
     *
     * @param vector  查询向量
     * @param filter  Milvus 标量过滤表达式，如 "level != 2"，可为 null
     * @param topK    返回数量
     */
    List<SearchHit> search(float[] vector, String filter, int topK);

    /** 确保 Collection 已创建（幂等） */
    void ensureCollection();

    /** 搜索结果，包含 Milvus 中存储的标量元数据 */
    record SearchHit(String contentId, float score, String contextType, String parentId, int level) {}
}
