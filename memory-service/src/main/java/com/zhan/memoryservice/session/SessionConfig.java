package com.zhan.memoryservice.session;

import com.zhan.memoryservice.config.MemoryServiceConfig;
import com.zhan.memoryservice.llm.EmbeddingProvider;
import com.zhan.memoryservice.llm.LLMProvider;
import com.zhan.common.llm.PromptManager;
import com.zhan.memoryservice.service.ContentService;
import com.zhan.memoryservice.storage.MetadataStore;
import com.zhan.memoryservice.storage.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;

@Configuration
public class SessionConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionConfig.class);

    @Bean
    public SessionStore sessionStore(MemoryServiceConfig config, ObjectMapper objectMapper) {
        Path workspace = Path.of(config.session().workspace());
        log.info("JsonlSessionStore 初始化: workspace={}", workspace.toAbsolutePath());
        return new JsonlSessionStore(workspace, objectMapper);
    }

    @Bean
    public MemoryExtractor memoryExtractor(LLMProvider llm, PromptManager prompts) {
        log.info("MemoryExtractor 初始化");
        return new MemoryExtractor(llm, prompts);
    }

    @Bean
    public MemoryDeduplicator memoryDeduplicator(VectorStore vectorStore, MetadataStore metadata,
                                                   EmbeddingProvider embedder, LLMProvider llm,
                                                   PromptManager prompts, MemoryServiceConfig config) {
        var memory = config.memory() != null
                ? config.memory()
                : new MemoryServiceConfig.MemoryConfig(null);
        var dedup = memory.dedup();
        log.info("MemoryDeduplicator 初始化: topK={}, similarityThreshold={}",
                dedup.safeTopK(), dedup.safeSimilarityThreshold());
        return new MemoryDeduplicator(vectorStore, metadata, embedder, llm, prompts, dedup);
    }

    @Bean
    public SessionService sessionService(SessionStore store, MemoryExtractor extractor,
                                          MemoryDeduplicator deduplicator,
                                          ContentService contentService,
                                          LLMProvider llm, PromptManager prompts) {
        log.info("SessionService 初始化");
        return new SessionService(store, extractor, deduplicator, contentService, llm, prompts);
    }
}
