package com.zhan.jarvis.server.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped SSE event hub for HTTP clients.
 */
public class SseEventHub {

    private static final Logger log = LoggerFactory.getLogger(SseEventHub.class);
    private static final Duration PING_INTERVAL = Duration.ofSeconds(20);

    private final Map<String, Sinks.Many<ServerSentEvent<Map<String, Object>>>> sinks =
            new ConcurrentHashMap<>();

    public Flux<ServerSentEvent<Map<String, Object>>> subscribe(String sessionId) {
        var sink = sink(sessionId);
        var connected = ServerSentEvent.<Map<String, Object>>builder()
                .event(SseEventTypes.CONNECTED)
                .data(event(SseEventTypes.CONNECTED, sessionId, "", "sse"))
                .build();
        var ping = Flux.interval(PING_INTERVAL)
                .map(i -> ServerSentEvent.<Map<String, Object>>builder()
                        .event(SseEventTypes.PING)
                        .data(event(SseEventTypes.PING, sessionId, "", "sse"))
                        .build());
        return Flux.concat(Flux.just(connected), Flux.merge(sink.asFlux(), ping))
                .doOnCancel(() -> log.debug("SSE 订阅取消: sessionId={}", sessionId));
    }

    public void publish(String sessionId, String type, String content, String source) {
        publish(sessionId, type, content, source, Map.of());
    }

    public void publish(String sessionId, String type, String content, String source, Map<String, Object> extra) {
        var data = event(type, sessionId, content, source);
        if (extra != null && !extra.isEmpty()) {
            data.putAll(extra);
        }
        var event = ServerSentEvent.<Map<String, Object>>builder()
                .event(type)
                .data(data)
                .build();
        var result = sink(sessionId).tryEmitNext(event);
        if (result.isFailure()) {
            log.debug("SSE 事件投递失败: sessionId={}, type={}, result={}", sessionId, type, result);
        }
    }

    private Sinks.Many<ServerSentEvent<Map<String, Object>>> sink(String sessionId) {
        return sinks.computeIfAbsent(sessionId, ignored ->
                Sinks.many().multicast().directBestEffort());
    }

    private Map<String, Object> event(String type, String sessionId, String content, String source) {
        var data = new LinkedHashMap<String, Object>();
        data.put("type", type);
        data.put("session_id", sessionId);
        data.put("content", content == null ? "" : content);
        data.put("source", source == null ? "" : source);
        data.put("created_at", Instant.now().toString());
        return data;
    }
}
