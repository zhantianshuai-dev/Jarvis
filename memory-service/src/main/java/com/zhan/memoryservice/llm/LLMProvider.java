package com.zhan.memoryservice.llm;

/**
 * LLM 调用接口 — 统一抽象，方便切换 provider。
 */
public interface LLMProvider {

    /** 使用配置的默认 temperature 调用 */
    String chat(String systemPrompt, String userPrompt);

    /** 指定 temperature 调用 */
    String chat(String systemPrompt, String userPrompt, double temperature);
}
