package com.zhan.memoryservice.service;

import com.zhan.memoryservice.config.MemoryServiceConfig;
import com.zhan.memoryservice.llm.LLMProvider;
import com.zhan.memoryservice.llm.EmbeddingProvider;
import com.zhan.common.llm.PromptManager;
import com.zhan.memoryservice.model.*;
import com.zhan.memoryservice.storage.MetadataStore;
import com.zhan.memoryservice.storage.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 内容写入编排 — 对标 Python ResourceProcessor + ContentWriteCoordinator。
 * <p>
 * 流程：校验 → 写入 H2 → (异步) LLM 生成 abstract+overview → Embedding → 写入 Milvus → 更新 H2
 * 异步部分使用虚拟线程。
 */
@Service
public class ContentService {

    private static final Logger log = LoggerFactory.getLogger(ContentService.class);

    private final MetadataStore metadata;
    private final VectorStore vector;
    private final LLMProvider llm;
    private final EmbeddingProvider embedder;
    private final PromptManager prompts;

    public ContentService(MetadataStore metadata, VectorStore vector,
                          LLMProvider llm, EmbeddingProvider embedder,
                          PromptManager prompts) {
        this.metadata = metadata;
        this.vector = vector;
        this.llm = llm;
        this.embedder = embedder;
        this.prompts = prompts;
    }

    /**
     * 写入内容，异步触发生成摘要和向量化。
     */
    public WriteResult write(WriteRequest req) {
        String contentId = req.contentId() != null && !req.contentId().isBlank()
                ? req.contentId() : UUID.randomUUID().toString().substring(0, 12);

        log.info("内容写入: contentId={}, contextType={}, contentLen={}",
                contentId, req.contextType(), req.content().length());

        // 1. 写入 H2
        var entry = ContextEntry.createNew(contentId, req.content(), req.contextType(), req.parentId());
        metadata.save(entry);

        // 2. 异步生成摘要 + 向量化
        Thread.startVirtualThread(() -> asyncProcess(contentId, req.content()));

        return new WriteResult(contentId, "queued", "queued");
    }

    /** 按 ID 查询条目（供 SessionService 读取 Working Memory） */
    public ContextEntry getById(String contentId) {
        return metadata.getById(contentId).orElse(null);
    }

    private void asyncProcess(String contentId, String content) {
        try {
            // 2a. LLM 生成 L0 摘要
            log.debug("生成摘要: contentId={}", contentId);
            String abstractText = llm.chat(
                    prompts.render("semantic/document_summary", Map.of("content", content)),
                    ""
            );
            log.debug("摘要完成: contentId={}, len={}", contentId, abstractText.length());

            // 2b. LLM 生成 L1 概览
            String overview = llm.chat(
                    prompts.render("semantic/overview_generation",
                            Map.of("content", content, "abstract", abstractText)),
                    ""
            );
            log.debug("概览完成: contentId={}, len={}", contentId, overview.length());

            // 2c. Embedding
            float[] vectorArr = embedder.embed(abstractText);
            log.debug("向量化完成: contentId={}, dim={}", contentId, vectorArr.length);

            // 2d. 插入 Milvus
            var entry = metadata.getById(contentId).orElse(null);
            if (entry == null) {
                log.warn("内容不存在，跳过向量写入: contentId={}", contentId);
                return;
            }
            vector.insert(contentId, vectorArr, entry.contextType().value(), entry.parentId(), 0);

            // 2e. 更新 H2 摘要
            metadata.updateSummaries(contentId, abstractText, overview);
            log.info("异步处理完成: contentId={}", contentId);

        } catch (Exception e) {
            log.error("异步处理失败: contentId={}", contentId, e);
        }
    }
}
