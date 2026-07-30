package com.zhan.memoryservice.llm;

/**
 * LLM 调用接口 — 统一抽象，方便切换服务提供商。
 */
public interface LLMProvider {

    /** 使用配置的默认温度参数调用 */
    String chat(String systemPrompt, String userPrompt);

    /** 指定温度参数调用 */
    String chat(String systemPrompt, String userPrompt, double temperature);
}
