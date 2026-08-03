package com.zhan.jarvis.agent.planner;

import com.zhan.jarvis.agent.RunMode;
import com.zhan.jarvis.config.JarvisConfig;

import java.util.List;

/**
 * 低成本判断本轮是否需要进入 Planner。
 */
public class PlannerGate {

    private static final List<String> COMPLEX_WORDS = List.of(
            "实现", "复现", "优化", "重构", "完善", "排查", "修复", "检查", "审核", "提交", "推送",
            "生成", "整理", "分析项目", "对齐", "迁移", "设计", "计划", "多步骤", "并行", "子agent", "子 agent",
            "implement", "refactor", "optimize", "fix", "review", "commit", "push", "plan"
    );
    private static final List<String> ACTION_WORDS = List.of(
            "查看", "读取", "修改", "写入", "执行", "测试", "编译", "提交", "推送", "删除", "创建",
            "read", "write", "edit", "run", "test", "build", "commit", "push", "delete"
    );
    private static final List<String> OBJECT_WORDS = List.of(
            "前端", "后端", "数据库", "接口", "jarvis", "memory-service", "git", "文件", "目录", "代码",
            "frontend", "backend", "database", "api", "repo", "repository"
    );

    private final JarvisConfig.PlannerConfig config;

    public PlannerGate(JarvisConfig.PlannerConfig config) {
        this.config = config;
    }

    public boolean shouldPlan(RunMode mode, String message) {
        if (config == null || !config.enabled() || mode == RunMode.CHAT) {
            return false;
        }
        if (mode == RunMode.SUPER_AGENT && config.superAgentAlwaysPlan()) {
            return true;
        }
        String text = message == null ? "" : message.strip().toLowerCase();
        if (text.length() < config.minMessageChars()) {
            return false;
        }
        int score = 0;
        score += countMatches(text, COMPLEX_WORDS) * 2;
        score += countMatches(text, ACTION_WORDS);
        score += countMatches(text, OBJECT_WORDS);
        if (containsAny(text, "然后", "并且", "同时", "以及", "再", "最后", "先", "after", "then", "and")) {
            score += 2;
        }
        if (containsAny(text, "commit", "push", "提交", "推送", "删除", "覆盖", "发布", "merge", "合并")) {
            score += 3;
        }
        return score >= config.complexityThreshold();
    }

    private int countMatches(String text, List<String> words) {
        int count = 0;
        for (String word : words) {
            if (text.contains(word.toLowerCase())) {
                count++;
            }
        }
        return count;
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
