package com.zhan.jarvis.server.router;

/**
 * 聊天附件。
 * path 是后端保存后的本地文件路径，只由 Jarvis 后端生成和信任。
 */
public record ChatAttachment(
        String id,
        String name,
        String contentType,
        long size,
        String path
) {}
