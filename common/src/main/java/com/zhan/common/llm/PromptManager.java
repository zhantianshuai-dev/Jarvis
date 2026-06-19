package com.zhan.common.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Prompt 模板管理器 — 加载 resources/prompts/ 下的 .st 模板文件。
 * <p>
 * .st 文件格式：
 * <pre>{@code
 * # temperature: 0.0
 * # description: 文档摘要生成
 * 系统提示词正文...
 * {{ 变量名 }}
 * {{#if 变量名}} 条件内容 {{/if}}
 * }</pre>
 */
@Component
public class PromptManager {

    private static final Logger log = LoggerFactory.getLogger(PromptManager.class);

    private final ResourceLoader resourceLoader;
    private final Map<String, Template> cache = new ConcurrentHashMap<>();

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{\\s*(\\w+)\\s*\\}\\}");
    private static final Pattern IF_PATTERN = Pattern.compile(
            "\\{\\{#if\\s+(\\w+)\\s*\\}\\}(.*?)\\{\\{/if\\}\\}", Pattern.DOTALL);

    public PromptManager(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * 渲染指定模板，返回系统提示词。
     *
     * @param templateName 模板路径，如 "semantic/document_summary"（不含 .st 后缀）
     * @param variables    模板变量
     * @return 渲染后的提示词文本
     */
    public String render(String templateName, Map<String, String> variables) {
        var template = loadTemplate(templateName);
        String result = template.body;
        result = handleIfBlocks(result, variables);
        result = replaceVariables(result, variables);
        log.debug("渲染模板 {} 完成，长度: {} 字符", templateName, result.length());
        return result;
    }

    /** 获取模板配置的 temperature，未配置则返回 0.0 */
    public double getTemperature(String templateName) {
        return loadTemplate(templateName).temperature;
    }

    // ---- 内部实现 ----

    private Template loadTemplate(String templateName) {
        return cache.computeIfAbsent(templateName, key -> {
            String path = "classpath:/prompts/" + key + ".st";
            try {
                Resource resource = resourceLoader.getResource(path);
                if (!resource.exists()) {
                    throw new IllegalArgumentException("模板文件不存在: " + path);
                }
                String raw = resource.getContentAsString(StandardCharsets.UTF_8);
                return parseTemplate(raw, key);
            } catch (IOException e) {
                throw new RuntimeException("读取模板文件失败: " + path, e);
            }
        });
    }

    private Template parseTemplate(String raw, String name) {
        double temperature = 0.0;
        StringBuilder body = new StringBuilder();

        for (String line : raw.lines().toList()) {
            if (line.startsWith("# temperature:")) {
                try {
                    temperature = Double.parseDouble(line.substring("# temperature:".length()).trim());
                } catch (NumberFormatException ignored) {}
            }
            else if (!line.startsWith("# description:")) {
                body.append(line).append('\n');
            }
        }

        if (body.isEmpty()) {
            throw new IllegalArgumentException("模板 [" + name + "] 内容为空");
        }

        return new Template(body.toString().stripTrailing(), temperature);
    }

    String replaceVariables(String template, Map<String, String> variables) {
        return VAR_PATTERN.matcher(template).replaceAll(match -> {
            String varName = match.group(1);
            String value = variables.getOrDefault(varName, "");
            return value != null ? value : "";
        });
    }

    /** 处理 {{#if var}} ... {{/if}} 条件块（变量非空时保留内容） */
    String handleIfBlocks(String template, Map<String, String> variables) {
        return IF_PATTERN.matcher(template).replaceAll(match -> {
            String varName = match.group(1);
            String blockContent = match.group(2);
            String value = variables.getOrDefault(varName, "");
            return (value != null && !value.isBlank()) ? blockContent : "";
        });
    }

    private record Template(String body, double temperature) {}
}