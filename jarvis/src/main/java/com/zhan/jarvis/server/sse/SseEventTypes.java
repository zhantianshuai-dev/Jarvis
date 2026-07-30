package com.zhan.jarvis.server.sse;

/**
 * HTTP 流式接口对外暴露的 SSE 事件名称。
 */
public final class SseEventTypes {

    public static final String CONNECTED = "connected";
    public static final String PING = "ping";
    public static final String TOKEN = "token";
    public static final String REASONING = "reasoning";
    public static final String TOOL_CALL = "tool_call";
    public static final String TOOL_RESULT = "tool_result";
    public static final String SUBAGENT_STATUS = "subagent_status";
    public static final String DONE = "done";
    public static final String ERROR = "error";
    public static final String MESSAGE = "message";

    private SseEventTypes() {
    }
}
