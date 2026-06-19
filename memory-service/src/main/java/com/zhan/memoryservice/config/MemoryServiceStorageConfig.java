package com.zhan.memoryservice.config;

import com.zhan.memoryservice.storage.InMemoryContextStore;
import com.zhan.memoryservice.tracker.TokenUsageTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@ConditionalOnProperty(prefix = "memory-service.postgres", name = "enabled", havingValue = "false", matchIfMissing = true)
public class MemoryServiceStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryServiceStorageConfig.class);

    @Bean
    @Primary
    public InMemoryContextStore inMemoryContextStore() {
        log.warn("PostgreSQL 已关闭，使用进程内存存储；内容和向量将在重启后丢失");
        return new InMemoryContextStore();
    }

    @Bean
    @Primary
    public TokenUsageTracker tokenUsageTracker() {
        return TokenUsageTracker.noop();
    }
}
