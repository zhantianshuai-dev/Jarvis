package com.zhan.memoryservice.llm;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PromptManagerTest {

    @Autowired
    private PromptManager promptManager;

    @Test
    void testRenderDocumentSummary() {
        var vars = Map.of("content", "Spring Boot 是一个 Java 框架，用于快速构建生产级应用。");
        String result = promptManager.render("semantic/document_summary", vars);

        assertNotNull(result);
        assertTrue(result.contains("Spring Boot 是一个 Java 框架"));
        assertFalse(result.contains("{{"), "变量应已被替换");
        System.out.println("=== 文档摘要模板渲染结果 ===");
        System.out.println(result);
    }

    @Test
    void testRenderOverviewWithAbstract() {
        var vars = Map.of(
                "content", "Spring Boot 提供了自动配置、起步依赖和 Actuator 监控等功能...",
                "abstract", "Spring Boot 是 Java 快速开发框架。");
        String result = promptManager.render("semantic/overview_generation", vars);

        assertNotNull(result);
        assertTrue(result.contains("Spring Boot 是 Java 快速开发框架"), "应包含 abstract 内容");
        System.out.println("=== 概览模板渲染结果（含 abstract） ===");
        System.out.println(result.substring(0, Math.min(500, result.length())) + "...");
    }

    @Test
    void testRenderOverviewWithoutAbstract() {
        var vars = Map.of("content", "Spring Boot 提供了自动配置功能...");
        String result = promptManager.render("semantic/overview_generation", vars);

        assertNotNull(result);
        assertFalse(result.contains("已知摘要"), "未传 abstract 时不应出现该标题");
        System.out.println("=== 概览模板渲染结果（无 abstract） ===");
        System.out.println(result.substring(0, Math.min(300, result.length())) + "...");
    }

    @Test
    void testIfBlockRemovedWhenVarMissing() {
        String template = "开头\n{{#if abstract}}摘要: {{ abstract }}{{/if}}\n结尾";
        var vars = Map.of("content", "test");
        String result = promptManager.handleIfBlocks(template, vars);

        assertFalse(result.contains("摘要"), "abstract 为空时，条件块应被移除");
        assertTrue(result.contains("开头"));
        assertTrue(result.contains("结尾"));
    }

    @Test
    void testTemperature() {
        assertEquals(0.0, promptManager.getTemperature("semantic/document_summary"), 0.01);
        assertEquals(0.0, promptManager.getTemperature("semantic/overview_generation"), 0.01);
    }

    @Test
    void testTemplateNotFound() {
        var ex = assertThrows(RuntimeException.class,
                () -> promptManager.render("nonexistent/template", Map.of()));
        assertTrue(ex.getMessage().contains("不存在"));
    }
}
