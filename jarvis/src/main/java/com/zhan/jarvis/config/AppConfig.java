package com.zhan.jarvis.config;

import com.zhan.jarvis.agent.AgentLoop;
import com.zhan.jarvis.agent.ContextBuilder;
import com.zhan.jarvis.bus.AgentMessageWorker;
import com.zhan.jarvis.bus.MessageBus;
import com.zhan.jarvis.channel.ChannelManager;
import com.zhan.jarvis.channel.FeishuChannel;
import com.zhan.jarvis.channel.HttpChannel;
import com.zhan.jarvis.cron.CronService;
import com.zhan.jarvis.git.WorktreeManager;
import com.zhan.jarvis.heartbeat.HeartbeatService;
import com.zhan.jarvis.hook.HookManager;
import com.zhan.jarvis.hook.impl.AgentTraceHook;
import com.zhan.jarvis.hook.impl.ExecSafetyHook;
import com.zhan.jarvis.hook.impl.GitPolicyHook;
import com.zhan.jarvis.hook.impl.ToolAuditHook;
import com.zhan.jarvis.llm.AgentLLMProvider;
import com.zhan.jarvis.llm.OpenAiAgentLLMProvider;
import com.zhan.jarvis.memory.MemoryServiceClient;
import com.zhan.jarvis.permission.ToolPermissionManager;
import com.zhan.jarvis.permission.AgentCheckpointStore;
import com.zhan.jarvis.sandbox.DirectBackend;
import com.zhan.jarvis.sandbox.SandboxBackend;
import com.zhan.jarvis.sandbox.SandboxManager;
import com.zhan.jarvis.server.sse.SseEventHub;
import com.zhan.jarvis.session.SessionManager;
import com.zhan.jarvis.skill.SkillsLoader;
import com.zhan.jarvis.subagent.SubagentManager;
import com.zhan.jarvis.task.TaskManager;
import com.zhan.jarvis.tool.ExternalMcpClient;
import com.zhan.jarvis.tool.LocalMcpServer;
import com.zhan.jarvis.tool.McpClient;
import com.zhan.jarvis.tool.McpServerConfig;
import com.zhan.jarvis.tool.SseTransport;
import com.zhan.jarvis.tool.StdioTransport;
import com.zhan.jarvis.tool.ToolRegistry;
import com.zhan.jarvis.tool.impl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Jarvis Bean 装配。
 * WebClient.Builder 由 common 模块的 WebClientConfig 提供，
 * ObjectMapper 由 Spring Boot WebFlux 自动配置。
 */
@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    public AppConfig(JarvisConfig config) {
        log.info("Jarvis 配置已加载:");
        log.info("  LLM: provider={}, model={}, baseUrl={}",
                config.llm().provider(), config.llm().model(), config.llm().apiBase());
        log.info("  memory-service: {}", config.memoryService().baseUrl());
        log.info("  Agent: name={}, maxIterations={}, workspace={}",
                config.agent().name(), config.agent().maxIterations(), config.agent().workspace());
    }

    // ---- 1.2 AgentLLMProvider ----

    @Bean
    public static AgentLLMProvider agentLLMProvider(JarvisConfig config, ObjectMapper objectMapper,
                                                     WebClient.Builder builder) {
        log.info("创建 AgentLLMProvider: model={}", config.llm().model());
        return new OpenAiAgentLLMProvider(config.llm(), objectMapper, builder);
    }

    // ---- 1.5 MemoryServiceClient ----

    @Bean
    public static MemoryServiceClient memoryServiceClient(JarvisConfig config, WebClient.Builder builder,
                                                           ObjectMapper objectMapper) {
        return new MemoryServiceClient(config.memoryService(), builder, objectMapper);
    }

    // ---- ImageGenClient ----

    @Bean
    public static ImageGenClient imageGenClient(JarvisConfig config, WebClient.Builder builder,
                                                  ObjectMapper objectMapper) {
        log.info("创建 ImageGenClient: model={}, apiBase={}", config.imageGen().model(), config.imageGen().apiBase());
        return new ImageGenClient(config.imageGen(), builder, objectMapper);
    }

    // ---- 1.6 Session（委托 memory-service 管理） ----

    @Bean
    public SessionManager sessionManager(MemoryServiceClient memoryClient) {
        return new SessionManager(memoryClient);
    }

    // ---- 2.5 Hook 系统 ----

    @Bean
    public HookManager hookManager() {
        var manager = new HookManager();
        manager.register(HookManager.AGENT_PRE_PROCESS, new AgentTraceHook());
        manager.register(HookManager.AGENT_POST_PROCESS, new AgentTraceHook());
        manager.register(HookManager.TOOL_PRE_CALL, new ExecSafetyHook());
        manager.register(HookManager.TOOL_PRE_CALL, new GitPolicyHook());
        manager.register(HookManager.TOOL_PRE_CALL, new ToolAuditHook());
        manager.register(HookManager.TOOL_POST_CALL, new ToolAuditHook());
        return manager;
    }

    // ---- 2.8 Sandbox 抽象 ----

    @Bean
    public SandboxBackend sandboxBackend() {
        return new DirectBackend();
    }

    @Bean
    public SandboxManager sandboxManager(SandboxBackend sandboxBackend) {
        return new SandboxManager(sandboxBackend);
    }

    @Bean
    public WorktreeManager worktreeManager(ObjectMapper objectMapper, JarvisConfig config) {
        return new WorktreeManager(objectMapper, config.agent().workspace());
    }

    @Bean
    public TaskManager taskManager(ObjectMapper objectMapper, JarvisConfig config) {
        return new TaskManager(objectMapper, config.agent().workspace());
    }

    // ---- 1.3 + 1.4 Tool 系统 ----

    @Bean
    public LocalMcpServer localMcpServer(ObjectMapper objectMapper, MemoryServiceClient memoryClient,
                                         ImageGenClient imageGenClient, SandboxManager sandboxManager,
                                         CronService cronService, WebClient.Builder builder,
                                         JarvisConfig config) {
        var server = new LocalMcpServer();
        // 基础工具（spawn 工具稍后通过 SpawnToolInitializer 注册，避免循环依赖）
        server.registerAll(
                new ReadFileTool(objectMapper, sandboxManager),
                new WriteFileTool(objectMapper, sandboxManager),
                new EditFileTool(objectMapper, sandboxManager),
                new ListDirTool(objectMapper, sandboxManager),
                new ExecTool(objectMapper, sandboxManager),
                new GitTool(objectMapper, Path.of("").toAbsolutePath().normalize().toString()),
                new WebFetchTool(objectMapper, builder),
                new MemorySearchTool(objectMapper, memoryClient),
                new MemoryRememberTool(objectMapper, memoryClient),
                new MemoryCommitTool(objectMapper, memoryClient),
                new CronTool(objectMapper, cronService),
                new ImageGenTool(objectMapper, imageGenClient)
        );
        var feishuConfig = config.channels() != null ? config.channels().feishu() : null;
        if (feishuConfig != null && feishuConfig.enabled()
                && hasText(feishuConfig.appId()) && hasText(feishuConfig.appSecret())) {
            server.register(new FeishuHistoryMessagesTool(objectMapper, feishuConfig));
        }
        log.info("LocalMcpServer: 注册 {} 个基础工具", server.toolCount());
        return server;
    }

    @Bean
    public ToolRegistry toolRegistry(LocalMcpServer localServer, JarvisConfig config,
                                      ObjectMapper objectMapper, WebClient.Builder builder,
                                      HookManager hookManager, ToolPermissionManager permissionManager) {
        return new ToolRegistry(localServer, createExternalMcpClients(config, objectMapper, builder), hookManager,
                permissionManager, objectMapper);
    }

    // ---- 1.9 SubagentManager ----

    @Bean
    public SubagentManager subagentManager(ToolRegistry toolRegistry, AgentLLMProvider llmProvider,
                                            MemoryServiceClient memoryClient, ObjectMapper objectMapper,
                                            WorktreeManager worktreeManager, TaskManager taskManager,
                                            SseEventHub sseEventHub, JarvisConfig config) {
        log.info("创建 SubagentManager");
        return new SubagentManager(toolRegistry, llmProvider, memoryClient, objectMapper,
                worktreeManager, taskManager, sseEventHub, config.agent().workspace());
    }

    /**
     * 注册 spawn 工具到 LocalMcpServer。
     * 使用 @DependsOn 确保 LocalMcpServer、ToolRegistry、SubagentManager 都已创建。
     * 这打破了 localMcpServer ↔ subagentManager 的循环依赖。
     */
    @Bean
    @DependsOn({"localMcpServer", "toolRegistry", "subagentManager"})
    public Object spawnToolInitializer(LocalMcpServer localServer, SubagentManager subagentManager,
                                        ObjectMapper objectMapper) {
        localServer.register(new SpawnTool(objectMapper, subagentManager));
        log.info("spawn 工具已注册到 LocalMcpServer (共 {} 个工具)", localServer.toolCount());
        return "spawn-tool-initialized";
    }

    // ---- 2.3 Skills 技能系统 ----

    @Bean
    public SkillsLoader skillsLoader(JarvisConfig config, ObjectMapper objectMapper) {
        log.info("SkillsLoader 初始化: workspace={}/skills", config.agent().workspace());
        return new SkillsLoader(java.nio.file.Path.of(config.agent().workspace()), objectMapper);
    }

    // ---- 1.7 ContextBuilder ----

    @Bean
    public ContextBuilder contextBuilder(JarvisConfig config, ToolRegistry toolRegistry,
                                          MemoryServiceClient memoryClient, SkillsLoader skillsLoader) {
        return new ContextBuilder(config.agent(), toolRegistry, memoryClient, skillsLoader);
    }

    // ---- 1.8 AgentLoop ----

    @Bean
    public AgentLoop agentLoop(JarvisConfig config, AgentLLMProvider llmProvider,
                                ToolRegistry toolRegistry, ContextBuilder contextBuilder,
                                SessionManager sessionManager, ObjectMapper objectMapper,
                                HookManager hookManager, AgentCheckpointStore checkpointStore) {
        log.info("创建 AgentLoop: maxIterations={}", config.agent().maxIterations());
        return new AgentLoop(config.agent(), llmProvider, toolRegistry, contextBuilder,
                sessionManager, objectMapper, hookManager, checkpointStore);
    }

    // ---- 2.6 MessageBus 解耦 ----

    @Bean
    public MessageBus messageBus() {
        return new MessageBus();
    }
    //这里会自动注入存入IoC容器的MessageBus
    @Bean
    public AgentMessageWorker agentMessageWorker(MessageBus messageBus, AgentLoop agentLoop,
                                                 ChannelManager channelManager) {
        var worker = new AgentMessageWorker(messageBus, agentLoop, channelManager);
        worker.start();  //直接启动loop，不断去消息队列中取任务
        return worker;
    }

    // ---- 2.7 Channel 抽象 ----

    @Bean
    public SseEventHub sseEventHub() {
        return new SseEventHub();
    }

    @Bean
    public HttpChannel httpChannel(MessageBus messageBus, SseEventHub sseEventHub) {
        return new HttpChannel(messageBus, sseEventHub);
    }

    @Bean
    public FeishuChannel feishuChannel(JarvisConfig config, MessageBus messageBus, ObjectMapper objectMapper) {
        var feishuConfig = config.channels() != null ? config.channels().feishu() : null;
        return new FeishuChannel(feishuConfig, messageBus, objectMapper);
    }

    @Bean
    public ChannelManager channelManager(HttpChannel httpChannel, FeishuChannel feishuChannel,
                                         JarvisConfig config) {
        var manager = new ChannelManager();
        manager.register(httpChannel);
        var feishuConfig = config.channels() != null ? config.channels().feishu() : null;
        if (feishuConfig != null && feishuConfig.enabled()) {
            manager.register(feishuChannel);
        }
        manager.startAll();
        return manager;
    }

    // ---- 2.9 Heartbeat 自主唤醒 ----

    @Bean
    public HeartbeatService heartbeatService(JarvisConfig config, MessageBus messageBus) {
        var service = new HeartbeatService(config.heartbeat(), config.agent().workspace(), messageBus);
        service.start();
        return service;
    }

    // ---- 2.10 Cron 定时任务 ----

    @Bean
    public CronService cronService(JarvisConfig config, MessageBus messageBus, ObjectMapper objectMapper) {
        var service = new CronService(config.cron(), config.agent().workspace(), messageBus, objectMapper);
        service.start();
        return service;
    }

    private static List<McpClient> createExternalMcpClients(JarvisConfig config, ObjectMapper objectMapper,
                                                            WebClient.Builder builder) {
        var mcp = config.mcp();
        if (mcp == null || !mcp.enabled() || mcp.externalServers() == null || mcp.externalServers().length == 0) {
            return List.of();
        }

        var clients = new ArrayList<McpClient>();
        for (var server : mcp.externalServers()) {
            var serverConfig = McpServerConfig.from(server);
            try {
                var transportName = serverConfig.transport() == null ? "" :
                        serverConfig.transport().toLowerCase(Locale.ROOT);
                var transport = switch (transportName) {
                    case "stdio" -> new StdioTransport(serverConfig, objectMapper);
                    case "sse", "http", "streamable-http" -> new SseTransport(serverConfig, objectMapper, builder);
                    default -> throw new IllegalArgumentException("不支持的 MCP transport: " + serverConfig.transport());
                };
                clients.add(new ExternalMcpClient(serverConfig, transport, objectMapper));
            } catch (Exception e) {
                log.warn("创建外部 MCP Client 失败: {} ({})", serverConfig.name(), e.getMessage());
                log.debug("创建外部 MCP Client 失败详情", e);
            }
        }
        log.info("外部 MCP Client 初始化完成: {} 个", clients.size());
        return clients;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
