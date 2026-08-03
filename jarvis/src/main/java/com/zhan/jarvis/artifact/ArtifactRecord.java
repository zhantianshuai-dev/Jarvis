package com.zhan.jarvis.artifact;

import java.time.Instant;
import java.util.Map;

/**
 * 会话级文件产物记录。
 * 输入上传和 Agent 输出文件都统一登记为 artifact，聊天上下文只需要引用路径和摘要。
 */
public record ArtifactRecord(
        String artifactId,
        String sessionId,
        String userId,
        String name,
        String path,
        String contentType,
        long size,
        String kind,
        String source,
        String summary,
        Map<String, Object> metadata,
        Instant createdAt
) {
}
