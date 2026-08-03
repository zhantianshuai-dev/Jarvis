package com.zhan.jarvis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jarvis 运行时配置。
 *
 * Spring Boot 4.0 + JDK 21 Record 自动映射 yaml 到嵌套 Record。
 */
@ConfigurationProperties(prefix = "jarvis")
public record JarvisConfig(
    LLMConfig llm,
    MemoryServiceConfig memoryService,
    AgentConfig agent,
    PlannerConfig planner,
    AuthConfig auth,
    McpConfig mcp,
    ChannelConfig channels,
    ImageGenConfig imageGen,
    VisionConfig vision,
    SandboxConfig sandbox,
    HeartbeatConfig heartbeat,
    CronConfig cron
) {

    /** LLM 模型配置 */
    public record LLMConfig(
        String provider,
        String apiKey,
        String apiBase,
        String model,
        double temperature,
        int maxTokens,
        boolean logRequestBody,
        RetryConfig retry,
        CircuitBreakerConfig circuitBreaker,
        FallbackProviderConfig[] fallbackProviders
    ) {
        public record RetryConfig(
            int maxRetries,
            long initialBackoffMs,
            long maxBackoffMs
        ) {}

        public record CircuitBreakerConfig(
            boolean enabled,
            int failureThreshold,
            long recoveryTimeoutMs
        ) {}

        public record FallbackProviderConfig(
            boolean enabled,
            String provider,
            String apiKey,
            String apiBase,
            String model
        ) {}
    }

    /** memory-service 连接配置 */
    public record MemoryServiceConfig(
        String baseUrl
    ) {}

    /** Agent 运行参数 */
    public record AgentConfig(
        String name,
        int maxIterations,
        String workspace,
        WorkspaceOption[] workspaces,
        ContextBudgetConfig contextBudget,
        String systemPromptTemplate
    ) {
        public record WorkspaceOption(
            String id,
            String label,
            String path
        ) {}

        public record ContextBudgetConfig(
            boolean enabled,
            int maxInputTokens,
            int maxToolResultChars,
            int maxContextBlockChars,
            int keepRecentMessages
        ) {}
    }

    /** Agent 任务规划配置 */
    public record PlannerConfig(
        boolean enabled,
        boolean superAgentAlwaysPlan,
        int complexityThreshold,
        int minMessageChars
    ) {}

    /** HTTP API 认证配置 */
    public record AuthConfig(
        boolean enabled,
        boolean initSchema,
        boolean registrationEnabled,
        String[] allowedOrigins,
        String bootstrapUsername,
        String bootstrapPassword,
        long tokenTtlSeconds,
        int maxFailedLogins,
        long lockSeconds
    ) {}

    /** MCP 工具配置 */
    public record McpConfig(
        boolean enabled,
        boolean localTools,
        ExternalMcpServer[] externalServers
    ) {
        public record ExternalMcpServer(
            String name,
            String transport,
            String command,
            String[] args,
            String url,
            java.util.Map<String, String> headers
        ) {}
    }

    /** 通道配置 */
    public record ChannelConfig(
        FeishuConfig feishu
    ) {
        public record FeishuConfig(
            boolean enabled,
            String appId,
            String appSecret,
            String botName,
            String encryptKey,
            String verificationToken,
            String[] allowFrom,
            boolean threadRequireMention
        ) {}
    }

    /** 图片生成配置 */
    public record ImageGenConfig(
        String apiBase,
        String apiKey,
        String model,
        String size
    ) {}

    /** 图片理解配置 */
    public record VisionConfig(
        boolean enabled,
        String apiBase,
        String apiKey,
        String model,
        int maxTokens
    ) {}

    /** 工具执行沙箱配置 */
    public record SandboxConfig(
        String backend,
        String baseUrl,
        String hostRoot,
        String sandboxRoot
    ) {}

    /** Heartbeat 自主唤醒配置 */
    public record HeartbeatConfig(
        boolean enabled,
        long intervalSeconds,
        String file,
        String sessionId
    ) {}

    /** Cron 定时任务配置 */
    public record CronConfig(
        boolean enabled,
        long pollSeconds,
        String file
    ) {}
}
