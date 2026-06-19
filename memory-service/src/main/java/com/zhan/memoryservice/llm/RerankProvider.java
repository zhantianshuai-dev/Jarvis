package com.zhan.memoryservice.llm;

import java.util.List;

/**
 * Rerank 重排序接口，对标 Python RerankClient。
 * 对候选文本列表进行语义重排序，返回与 query 的相关性分数。
 */
public interface RerankProvider {

    /**
     * 对一批文档重排序。
     *
     * @param query     查询文本
     * @param documents 候选文档列表（通常用 abstract）
     * @return 与 documents 一一对应的相关性分数
     */
    List<Float> rerank(String query, List<String> documents);

    /** 是否可用（provider 未配置时返回 false） */
    default boolean isAvailable() {
        return true;
    }
}
