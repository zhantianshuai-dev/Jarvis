package com.zhan.jarvis.session;

/**
 * 会话标识 — Jarvis 不管理上下文持久化，
 * 持久化由 memory-service 的 JSONL 存储负责。
 */
public record Session(String id) {
}