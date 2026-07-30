package com.zhan.jarvis.llm;

import java.util.List;
import reactor.core.publisher.Flux;

/**
 * Agent LLM 服务提供商接口 — 支持多轮消息 + 工具调用。
 * <p>
 * 与 memory-service 的 LLM 服务提供商不同：后者只返回纯文本，
 * Agent 需要支持工具调用往返。
 */
public interface AgentLLMProvider {

    /**
     * 发送多轮对话消息，可选工具定义。
     *
     * @param messages 对话历史（系统/用户/助手/工具混合）
     * @param tools    可用工具列表（null 或空列表 = 不传工具参数）
     * @return LLM 响应（可能包含工具调用）
     */
    ChatResponse chat(List<Message> messages, List<ToolDefinition> tools);

    /**
     * 使用 OpenAI 兼容 SSE 响应进行流式对话。
     */
    Flux<ChatStreamDelta> streamChat(List<Message> messages, List<ToolDefinition> tools);
}
