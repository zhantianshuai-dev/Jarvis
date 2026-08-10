package com.zhan.jarvis.eval;

import com.zhan.jarvis.config.JarvisConfig;
import com.zhan.jarvis.memory.MemoryServiceClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

/**
 * eval Profile 下替换远程会话依赖，确保评测不会污染日常记忆服务。
 */
@Configuration
@Profile("eval")
public class EvaluationConfiguration {

    @Bean
    @Primary
    public MemoryServiceClient evaluationMemoryServiceClient(JarvisConfig config, WebClient.Builder builder,
                                                             ObjectMapper objectMapper) {
        return new EvaluationMemoryServiceClient(config.memoryService(), builder, objectMapper);
    }
}
