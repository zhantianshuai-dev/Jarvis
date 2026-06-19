package com.zhan.memoryservice.model;

import java.util.List;

/**
 * 关联上下文，对标 Python RelatedContext。
 */
public record RelatedContext(String contentId, String abstractText) {}
