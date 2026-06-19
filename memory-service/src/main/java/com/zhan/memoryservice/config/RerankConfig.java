package com.zhan.memoryservice.config;

import com.zhan.memoryservice.llm.BailianRerankProvider;
import com.zhan.memoryservice.llm.RerankProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Configuration
public class RerankConfig {

    private static final Logger log = LoggerFactory.getLogger(RerankConfig.class);

    /**
     * RerankProvider — 根据配置决定启用百炼 Rerank 或 no-op。
     * WebClient.Builder 由 common 模块的 WebClientConfig 提供。
     */
    @Bean
    public RerankProvider rerankProvider(WebClient.Builder wcb, MemoryServiceConfig config) {
        var rc = config.rerank();
        if (rc.isAvailable()) {
            log.info("Rerank 已启用: provider={}, model={}", rc.provider(), rc.model());
            return new BailianRerankProvider(wcb, rc.apiBase(), rc.apiKey(), rc.model());
        }
        log.info("Rerank 未配置，使用 no-op");
        return new NoOpRerankProvider();
    }

    /** Rerank 未配置时的空实现 */
    static class NoOpRerankProvider implements RerankProvider {
        @Override
        public List<Float> rerank(String query, List<String> documents) {
            return List.of();
        }

        @Override
        public boolean isAvailable() {
            return false;
        }
    }
}