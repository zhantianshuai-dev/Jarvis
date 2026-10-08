package com.zhan.jarvis.config;

import com.zhan.jarvis.agent.AgentLoop;
import com.zhan.jarvis.agent.ContextBuilder;
import com.zhan.jarvis.agent.control.ActiveRunRegistry;
import com.zhan.jarvis.agent.control.RunInterruptionService;
import com.zhan.jarvis.bus.AgentMessageWorker;
import com.zhan.jarvis.bus.MessageBus;
import com.zhan.jarvis.concurrency.ConcurrencyController;
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
import com.zhan.jarvis.agent.middleware.AgentMiddlewareChain;
import com.zhan.jarvis.agent.middleware.RuntimeContextBudgetMiddleware;
import com.zhan.jarvis.agent.planner.PlanManager;
import com.zhan.jarvis.agent.planner.Planner;
import com.zhan.jarvis.agent.event.JsonlRunEventStore;
import com.zhan.jarvis.agent.event.RunEventStore;
import com.zhan.jarvis.agent.loop.ToolResultStore;
import com.zhan.jarvis.artifact.ArtifactManager;
import com.zhan.jarvis.permission.ToolPermissionManager;
import com.zhan.jarvis.permission.AgentCheckpointStore;
import com.zhan.jarvis.sandbox.DirectBackend;
import com.zhan.jarvis.sandbox.HttpSandboxBackend;
import com.zhan.jarvis.sandbox.OsSandboxBackend;
import com.zhan.jarvis.sandbox.SandboxBackend;
import com.zhan.jarvis.sandbox.SandboxManager;
import com.zhan.jarvis.server.sse.SseEventHub;
import com.zhan.jarvis.session.SessionFileSpaceManager;
import com.zhan.jarvis.session.SessionManager;
import com.zhan.jarvis.skill.SkillsLoader;
import com.zhan.jarvis.subagent.SubagentManager;
import com.zhan.jarvis.task.TaskManager;
import com.zhan.jarvis.todo.TodoManager;
import com.zhan.jarvis.tool.ExternalMcpClient;
import com.zhan.jarvis.tool.LocalMcpServer;
import com.zhan.jarvis.tool.McpClient;
import com.zhan.jarvis.tool.McpServerConfig;
import com.zhan.jarvis.tool.SseTransport;
import com.zhan.jarvis.tool.StdioTransport;
import com.zhan.jarvis.tool.ToolRegistry;
import com.zhan.jarvis.tool.impl.*;
import com.zhan.jarvis.vision.VisionClient;
import com.zhan.jarvis.workspace.WorkspaceResolver;
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

    // ---- 1.2 Agent LLM 服务提供商 ----

    @Bean
    public ConcurrencyController concurrencyController(JarvisConfig config) {
        return new ConcurrencyController(config.concurrency());
    }

    @Bean
    public static AgentLLMProvider agentLLMProvider(JarvisConfig config, ObjectMapper objectMapper,
                                                     WebClient.Builder builder,
                                                     ConcurrencyController concurrencyController) {
        var llmConfig = effectiveLlmConfig(config);
        log.info("创建 AgentLLMProvider: model={}", llmConfig.model());
        return new OpenAiAgentLLMProvider(llmConfig, objectMapper, builder, concurrencyController);
    }

    // ---- 1.5 记忆服务客户端 ----

    @Bean
    public static MemoryServiceClient memoryServiceClient(JarvisConfig config, WebClient.Builder builder,
                                                           ObjectMapper objectMapper,
                                                           ConcurrencyController concurrencyController) {
        return new MemoryServiceClient(config.memoryService(), builder, objectMapper, concurrencyController);
    }

    // ---- 图片生成客户端 ----

    @Bean
    public static ImageGenClient imageGenClient(JarvisConfig config, WebClient.Builder builder,
                                                  ObjectMapper objectMapper) {
        log.info("创建 ImageGenClient: model={}, apiBase={}", config.imageGen().model(), config.imageGen().apiBase());
        return new ImageGenClient(config.imageGen(), builder, objectMapper);
    }

    @Bean
    public static VisionClient visionClient(JarvisConfig config, WebClient.Builder builder,
                                            ObjectMapper objectMapper) {
        return new VisionClient(config.vision(), builder, objectMapper);
    }

    // ---- 1.6 会话（委托 memory-service 管理） ----

    @Bean
    public SessionFileSpaceManager sessionFileSpaceManager(JarvisConfig config) {
        return new SessionFileSpaceManager(config.agent().workspace());
    }

    @Bean
    public SessionManager sessionManager(MemoryServiceClient memoryClient, SessionFileSpaceManager fileSpaceManager) {
        return new SessionManager(memoryClient, fileSpaceManager);
    }

    @Bean
    public ToolResultStore toolResultStore(SessionFileSpaceManager fileSpaceManager) {
        return new ToolResultStore(fileSpaceManager);
    }

    @Bean
    public ArtifactManager artifactManager(SessionFileSpaceManager fileSpaceManager, ObjectMapper objectMapper) {
        return new ArtifactManager(fileSpaceManager, objectMapper);
    }

    @Bean
    public TodoManager todoManager(SessionFileSpaceManager fileSpaceManager, ObjectMapper objectMapper) {
        return new TodoManager(fileSpaceManager, objectMapper);
    }

    @Bean
    public PlanManager planManager(SessionFileSpaceManager fileSpaceManager, ObjectMapper objectMapper) {
        return new PlanManager(fileSpaceManager, objectMapper);
    }

    @Bean
    public Planner planner(JarvisConfig config, AgentLLMProvider llmProvider, ObjectMapper objectMapper) {
        return new Planner(config.planner(), llmProvider, objectMapper);
    }

    // ---- 2.5 Hook 系统 ----

    @Bean
    public HookManager hookManager(ConcurrencyController concurrencyController) {
        var manager = new HookManager(concurrencyController);
        manager.register(HookManager.AGENT_PRE_PROCESS, new AgentTraceHook());
        manager.register(HookManager.AGENT_POST_PROCESS, new AgentTraceHook());
        manager.register(HookManager.TOOL_PRE_CALL, new ExecSafetyHook());
        manager.register(HookManager.TOOL_PRE_CALL, new GitPolicyHook());
        manager.register(HookManager.TOOL_PRE_CALL, new ToolAuditHook());
        manager.register(HookManager.TOOL_POST_CALL, new ToolAuditHook());
        return manager;
    }

    // ---- 2.8 沙箱抽象 ----

    @Bean
    public SandboxBackend sandboxBackend(JarvisConfig config, WebClient.Builder builder) {
        var sandbox = config.sandbox();
        String backend = sandbox == null || sandbox.backend() == null ? "os" : sandbox.backend().trim();
        if ("http".equalsIgnoreCase(backend) || "docker".equalsIgnoreCase(backend)) {
            String hostRoot = sandbox.hostRoot() == null || sandbox.hostRoot().isBlank()
                    ? config.agent().workspace()
                    : sandbox.hostRoot();
            String sandboxRoot = sandbox.sandboxRoot() == null || sandbox.sandboxRoot().isBlank()
                    ? "/workspace"
                    : sandbox.sandboxRoot();
            log.info("创建 HTTP SandboxBackend: baseUrl={}, hostRoot={}, sandboxRoot={}",
                    sandbox.baseUrl(), hostRoot, sandboxRoot);
            return new HttpSandboxBackend(builder, sandbox.baseUrl(), hostRoot, sandboxRoot);
        }
        if ("os".equalsIgnoreCase(backend) || "seatbelt".equalsIgnoreCase(backend)) {
            var os = sandbox == null ? null : sandbox.os();
            String mode = os == null || os.mode() == null || os.mode().isBlank()
                    ? "workspace-write" : os.mode();
            boolean networkAccess = os != null && os.networkAccess();
            boolean allowTempWrite = os == null || os.allowTempWrite();
            int timeoutSeconds = os == null ? 60 : os.timeoutSeconds();
            int maxOutputChars = os == null ? 100_000 : os.maxOutputChars();
            log.info("创建 OS SandboxBackend: type=macOS Seatbelt, mode={}, networkAccess={}, allowTempWrite={}",
                    mode, networkAccess, allowTempWrite);
            return new OsSandboxBackend(mode, networkAccess, allowTempWrite, timeoutSeconds, maxOutputChars);
        }
        if ("direct".equalsIgnoreCase(backend)) {
            log.warn("创建 Direct SandboxBackend，该模式不提供操作系统级隔离");
            return new DirectBackend();
        }
        throw new IllegalArgumentException("不支持的 sandbox backend: " + backend
                + "，可选值为 direct、http/docker、os/seatbelt");
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
    public WorkspaceResolver workspaceResolver(JarvisConfig config) {
        return new WorkspaceResolver(config.agent());
    }

    @Bean
    public TaskManager taskManager(ObjectMapper objectMapper, JarvisConfig config) {
        return new TaskManager(objectMapper, config.agent().workspace());
    }

    // ---- 1.3 + 1.4 工具系统 ----

    @Bean
    public LocalMcpServer localMcpServer(ObjectMapper objectMapper, MemoryServiceClient memoryClient,
                                         ImageGenClient imageGenClient, SandboxManager sandboxManager,
                                         CronService cronService, WebClient.Builder builder,
                                         JarvisConfig config, TodoManager todoManager, ToolResultStore toolResultStore) {
        var server = new LocalMcpServer();
        // 基础工具（spawn 工具稍后通过派生工具初始化器注册，避免循环依赖）
        server.registerAll(
                new ReadFileTool(objectMapper, sandboxManager, toolResultStore),
                new WriteFileTool(objectMapper, sandboxManager),
                new EditFileTool(objectMapper, sandboxManager),
                new ListDirTool(objectMapper, sandboxManager),
                new ExecTool(objectMapper, sandboxManager),
                new GitTool(objectMapper, sandboxManager, Path.of("").toAbsolutePath().normalize().toString()),
                new WebFetchTool(objectMapper, builder),
                new MemorySearchTool(objectMapper, memoryClient),
                new MemoryRememberTool(objectMapper, memoryClient),
                new MemoryCommitTool(objectMapper, memoryClient),
                new TodoUpdateTool(objectMapper, todoManager),
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
                                      HookManager hookManager, ToolPermissionManager permissionManager,
                                      ConcurrencyController concurrencyController) {
        return new ToolRegistry(localServer, createExternalMcpClients(config, objectMapper, builder), hookManager,
                permissionManager, objectMapper, concurrencyController);
    }

    // ---- 1.9 子 Agent 管理器 ----

    @Bean
    public SubagentManager subagentManager(ToolRegistry toolRegistry, AgentLLMProvider llmProvider,
                                            MemoryServiceClient memoryClient, ObjectMapper objectMapper,
                                            WorktreeManager worktreeManager, TaskManager taskManager,
                                            SseEventHub sseEventHub, JarvisConfig config,
                                            ConcurrencyController concurrencyController) {
        long maxRuntimeSeconds = config.subagent() != null && config.subagent().maxRuntimeSeconds() > 0
                ? config.subagent().maxRuntimeSeconds() : 600;
        log.info("创建 SubagentManager: maxRuntimeSeconds={}", maxRuntimeSeconds);
        return new SubagentManager(toolRegistry, llmProvider, memoryClient, objectMapper,
                worktreeManager, taskManager, sseEventHub, config.agent().workspace(), concurrencyController,
                maxRuntimeSeconds);
    }

    /**
     * 注册 spawn 工具到本地 MCP 服务。
     * 使用 @DependsOn 确保本地 MCP 服务、工具注册表、子 Agent 管理器都已创建。
     * 这打破了本地 MCP 服务 ↔ 子 Agent 管理器的循环依赖。
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

    // ---- 1.7 上下文构建器 ----

    @Bean
    public ContextBuilder contextBuilder(JarvisConfig config, ToolRegistry toolRegistry,
                                          MemoryServiceClient memoryClient, SkillsLoader skillsLoader) {
        return new ContextBuilder(config.agent(), toolRegistry, memoryClient, skillsLoader);
    }

    // ---- 1.8 Agent 循环 ----

    @Bean
    public AgentMiddlewareChain agentMiddlewareChain(JarvisConfig config) {
        return new AgentMiddlewareChain(List.of(
                new RuntimeContextBudgetMiddleware(config.agent().contextBudget())
        ));
    }

    @Bean
    public RunEventStore runEventStore(JarvisConfig config, ObjectMapper objectMapper) {
        return new JsonlRunEventStore(Path.of(config.agent().workspace()), objectMapper);
    }

    @Bean
    public ActiveRunRegistry activeRunRegistry(JarvisConfig config, ObjectMapper objectMapper) {
        return new ActiveRunRegistry(Path.of(config.agent().workspace()), objectMapper);
    }

    @Bean
    public RunInterruptionService runInterruptionService(ActiveRunRegistry runRegistry, MessageBus messageBus,
                                                          AgentCheckpointStore checkpointStore,
                                                          ToolPermissionManager permissionManager,
                                                          SubagentManager subagentManager) {
        return new RunInterruptionService(runRegistry, messageBus, checkpointStore, permissionManager,
                subagentManager);
    }

    @Bean
    public AgentLoop agentLoop(JarvisConfig config, AgentLLMProvider llmProvider,
                                ToolRegistry toolRegistry, ContextBuilder contextBuilder,
                                SessionManager sessionManager, ObjectMapper objectMapper,
                                HookManager hookManager, AgentCheckpointStore checkpointStore,
                                WorkspaceResolver workspaceResolver,
                                AgentMiddlewareChain middlewareChain,
                                RunEventStore runEventStore,
                                ArtifactManager artifactManager,
                                Planner planner,
                                PlanManager planManager,
                                TodoManager todoManager,
                                ToolResultStore toolResultStore,
                                ConcurrencyController concurrencyController,
                                ActiveRunRegistry activeRunRegistry) {
        log.info("创建 AgentLoop: maxIterations={}", config.agent().maxIterations());
        return new AgentLoop(config.agent(), llmProvider, toolRegistry, contextBuilder,
                sessionManager, objectMapper, hookManager, checkpointStore, workspaceResolver, middlewareChain,
                runEventStore, artifactManager, planner, planManager, todoManager, toolResultStore,
                concurrencyController, activeRunRegistry);
    }

    // ---- 2.6 消息总线解耦 ----

    @Bean
    public MessageBus messageBus(JarvisConfig config) {
        var concurrency = config.concurrency();
        int capacity = concurrency != null && concurrency.messageQueueCapacity() > 0
                ? concurrency.messageQueueCapacity() : 256;
        return new MessageBus(capacity);
    }
    // 这里会自动注入 IoC 容器中的消息总线。
    @Bean(destroyMethod = "stop")
    public AgentMessageWorker agentMessageWorker(MessageBus messageBus, AgentLoop agentLoop,
                                                 ChannelManager channelManager, JarvisConfig config) {
        var concurrency = config.concurrency();
        int maxPending = concurrency != null && concurrency.maxQueuedAgents() > 0
                ? concurrency.maxQueuedAgents() : 128;
        int maxPendingPerUser = concurrency != null && concurrency.maxQueuedAgentsPerUser() > 0
                ? concurrency.maxQueuedAgentsPerUser() : 16;
        int maxPendingPerSession = concurrency != null && concurrency.maxQueuedAgentsPerSession() > 0
                ? concurrency.maxQueuedAgentsPerSession() : 8;
        var worker = new AgentMessageWorker(messageBus, agentLoop, channelManager,
                maxPending, maxPendingPerUser, maxPendingPerSession);
        worker.start();  // 直接启动循环，不断从消息队列中取任务。
        return worker;
    }

    // ---- 2.7 通道抽象 ----

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

    private static JarvisConfig.LLMConfig effectiveLlmConfig(JarvisConfig config) {
        var vision = config.vision();
        if (vision == null || !vision.enabled()) {
            return config.llm();
        }
        log.info("视觉开关已启用，主 Agent LLM 切换到视觉模型: model={}, apiBase={}",
                vision.model(), vision.apiBase());
        return new JarvisConfig.LLMConfig(
                "bailian-vision",
                vision.apiKey(),
                vision.apiBase(),
                vision.model(),
                config.llm().temperature(),
                config.llm().maxTokens(),
                config.llm().logRequestBody(),
                config.llm().retry(),
                config.llm().circuitBreaker(),
                config.llm().fallbackProviders()
        );
    }
}
