package com.zhan.jarvis.agent.event;

/**
 * Agent 运行事件存储。
 */
public interface RunEventStore {

    RunEventStore NOOP = event -> {
    };

    void append(RunEvent event);
}
