package com.zhan.memoryservice.retrieve;

import java.time.Duration;
import java.time.Instant;

/**
 * 冷热度打分算法，对标 Python memory_lifecycle.py → hotness_score()。
 *
 * <pre>
 *   score = sigmoid(log1p(activeCount)) × timeDecay(updatedAt)
 * </pre>
 * 频率因子用 sigmoid 压缩量级，时间因子用指数衰减（半衰期默认 7 天）。
 * 返回值永远在 [0.0, 1.0]。
 */
public class HotnessScorer {

    private final double halfLifeDays;

    public HotnessScorer(double halfLifeDays) {
        this.halfLifeDays = halfLifeDays;
    }

    /**
     * @param activeCount 被检索/访问次数
     * @param updatedAt   最后更新时间，null 时返回 0.0
     * @return 0.0 ~ 1.0
     */
    public double score(int activeCount, Instant updatedAt) {
        return score(activeCount, updatedAt, Instant.now());
    }

    // 包级可见，允许注入 now 方便测试
    double score(int activeCount, Instant updatedAt, Instant now) {
        // 频率: sigmoid(log1p(activeCount))
        double freq = 1.0 / (1.0 + Math.exp(-Math.log1p(activeCount)));

        // 时间衰减
        if (updatedAt == null) return 0.0;

        long ageSeconds = Duration.between(updatedAt, now).getSeconds();
        double ageDays = Math.max(ageSeconds / 86400.0, 0.0);
        double decayRate = Math.log(2) / halfLifeDays;
        double recency = Math.exp(-decayRate * ageDays);

        return freq * recency;
    }
}
