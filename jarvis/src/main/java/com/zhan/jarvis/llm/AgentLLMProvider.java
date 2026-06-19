package com.zhan.jarvis.llm;

import java.util.List;
import reactor.core.publisher.Flux;

/**
 * Agent LLM Provider 接口 — 支持多轮消息 + 工具调用。
 * <p>
 * 与 memory-service 的 LLMProvider 不同：那个只返回纯文本（chat(sys, user) → String），
 * Agent 需要 tool_calls 往返。
 */
public interface AgentLLMProvider {

    /**
     * 发送多轮对话消息，可选工具定义。
     *
     * @param messages 对话历史（sys/user/assistant/tool 混合）
     * @param tools    可用工具列表（null 或空列表 = 不传 tools 参数）
     * @return LLM 响应（可能包含 tool_calls）
     */
    ChatResponse chat(List<Message> messages, List<ToolDefinition> tools);

    /**
     * Streaming chat using OpenAI-compatible SSE responses.
     */
    Flux<ChatStreamDelta> streamChat(List<Message> messages, List<ToolDefinition> tools);
}
