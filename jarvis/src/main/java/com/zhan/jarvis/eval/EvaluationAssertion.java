package com.zhan.jarvis.eval;

/**
 * 场景断言。不同 type 只读取自身需要的字段。
 */
public record EvaluationAssertion(
        String type,
        String expected,
        String path,
        Integer max
) {
}
