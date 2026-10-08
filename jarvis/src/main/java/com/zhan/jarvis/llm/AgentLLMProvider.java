package com.zhan.jarvis.llm;

import com.zhan.jarvis.agent.control.TurnCancellationToken;

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
     * 带 Run 取消信号的同步调用。旧实现无需立即改造也能获得调用前后的取消检查。
     */
    default ChatResponse chat(List<Message> messages, List<ToolDefinition> tools,
                              TurnCancellationToken cancellationToken) {
        TurnCancellationToken token = cancellationToken == null
                ? TurnCancellationToken.none() : cancellationToken;
        token.throwIfCancellationRequested();
        ChatResponse response = chat(messages, tools);
        token.throwIfCancellationRequested();
        return response;
    }

    /**
     * 使用 OpenAI 兼容 SSE 响应进行流式对话。
     */
    Flux<ChatStreamDelta> streamChat(List<Message> messages, List<ToolDefinition> tools);

    /**
     * 带 Run 取消信号的流式调用。具体 Provider 可覆盖此方法并主动取消底层 HTTP 订阅。
     */
    default Flux<ChatStreamDelta> streamChat(List<Message> messages, List<ToolDefinition> tools,
                                             TurnCancellationToken cancellationToken) {
        TurnCancellationToken token = cancellationToken == null
                ? TurnCancellationToken.none() : cancellationToken;
        return Flux.defer(() -> {
            token.throwIfCancellationRequested();
            return streamChat(messages, tools)
                    .doOnNext(ignored -> token.throwIfCancellationRequested());
        });
    }
}
