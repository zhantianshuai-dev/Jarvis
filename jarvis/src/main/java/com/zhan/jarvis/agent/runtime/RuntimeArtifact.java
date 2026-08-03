package com.zhan.jarvis.agent.runtime;

/**
 * 单次运行产生的输出产物，例如文档、图片、报告或代码文件。
 * 后续前端和各类 Channel 只需要根据 artifact 引用展示或发送文件。
 */
public record RuntimeArtifact(
        String id,
        String name,
        String path,
        String contentType,
        long size,
        String summary
) {
}
