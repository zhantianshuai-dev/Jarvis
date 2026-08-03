package com.zhan.jarvis.agent.runtime;

/**
 * 单次运行中可被 Agent 使用的输入文件引用。
 * 只保存文件元信息和路径，避免把大文件内容直接塞进上下文。
 */
public record RuntimeFileRef(
        String id,
        String name,
        String path,
        String contentType,
        long size,
        String kind
) {
}
