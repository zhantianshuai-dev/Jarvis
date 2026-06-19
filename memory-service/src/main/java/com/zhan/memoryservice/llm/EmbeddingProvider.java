package com.zhan.memoryservice.llm;

/**
 * Embedding 调用接口 — 文本转向量。
 */
public interface EmbeddingProvider {

    /** 将文本转为向量 */
    float[] embed(String text);

    /** 返回向量维度 */
    int dimension();
}
