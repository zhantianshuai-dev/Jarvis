package com.zhan.memoryservice.storage;

import com.zhan.memoryservice.model.ContextEntry;
import com.zhan.memoryservice.model.ContextType;

import java.util.List;
import java.util.Optional;

/**
 * 元数据存储接口 — 管理 content 条目的 CRUD 和查询。
 */
public interface MetadataStore {

    /** 保存新条目（不含 abstract/overview，由异步生成后更新） */
    void save(ContextEntry entry);

    /** 更新摘要和概览（异步 LLM 生成后回调） */
    void updateSummaries(String contentId, String abstractText, String overview);

    /** 按 ID 查询完整条目 */
    Optional<ContextEntry> getById(String contentId);

    /** 批量按 ID 查询 */
    List<ContextEntry> getByIds(List<String> contentIds);

    /** 按类型分页查询 */
    List<ContextEntry> listByType(ContextType type, int limit, int offset);

    /** 查询指定目录下的子条目 */
    List<ContextEntry> listChildren(String parentId, int limit);

    /** 递增访问计数（用于冷热度计算） */
    void incrementActiveCount(String contentId);

    /** 删除条目 */
    void delete(String contentId);
}
