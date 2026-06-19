package com.zhan.jarvis.tool.impl;

import com.zhan.jarvis.tool.McpTool;
import com.zhan.jarvis.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * imagegen 工具 — 对标 Codex $imagegen，调用 gpt-image-2 模型生成图片。
 * <p>
 * 支持基于参考图片的图像生成：传入 pets 照片等参考图片路径，
 * gpt-image-2 根据参考图片和 prompt 描述生成目标图片。
 */
public class ImageGenTool implements McpTool {

    private static final Logger log = LoggerFactory.getLogger(ImageGenTool.class);

    private final ObjectMapper mapper;
    private final ImageGenClient client;

    public ImageGenTool(ObjectMapper mapper, ImageGenClient client) {
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public String name() {
        return "imagegen";
    }

    @Override
    public String description() {
        return "基于文本描述和参考图片生成新图片。用于生成宠物形象、角色设计、风格化头像等。" +
               "重要：参考图片URL必须通过 reference_images 参数传入（数组形式），不要在prompt中写URL。" +
               "参数: prompt (生成描述), reference_images (参考图片URL或路径数组, 可选)";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = mapper.createObjectNode()
                .put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("prompt")
                .put("type", "string")
                .put("description", "图片生成描述，描述你想要的图片内容和风格");

        var refArray = props.putObject("reference_images");
        refArray.put("type", "array");
        refArray.put("description", "参考图片的绝对路径列表，用于 image-to-image 生成");
        var refItems = refArray.putObject("items");
        refItems.put("type", "string");

        schema.putArray("required").add("prompt");
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        String prompt = (String) arguments.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return "错误: 缺少 prompt 参数";
        }

        // 提取参考图片路径列表，相对路径自动拼上 workspace
        List<String> referencePaths = new ArrayList<>();
        Object refObj = arguments.get("reference_images");
        if (refObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String rawPath && !rawPath.isBlank()) {
                    referencePaths.add(resolveRefPath(rawPath, ctx.workspaceDir()));
                }
            }
        }

        // 兜底：如果 prompt 中包含 URL，自动提取到参考图列表
        var urlPattern = Pattern.compile("https?://[^\\s]+");
        var urlMatcher = urlPattern.matcher(prompt);
        while (urlMatcher.find()) {
            String url = urlMatcher.group();
            if (!referencePaths.contains(url)) {
                referencePaths.add(url);
                log.info("从 prompt 中自动提取参考图片 URL: {}", url);
            }
        }

        log.info("imagegen: prompt={}, referenceImages={}", prompt, referencePaths.size());

        try {
            String outputPath = client.generate(prompt, referencePaths, ctx.workspaceDir());
            return "图片已生成: " + outputPath;
        } catch (Exception e) {
            log.error("imagegen 失败: {}", e.getMessage(), e);
            return "图片生成失败: " + e.getMessage();
        }
    }

    /** URL 原样返回；本地路径先尝试相对 workspace 解析，文件不存在时递归搜索 workspace 下同名文件 */
    private static String resolveRefPath(String rawPath, String workspaceDir) {
        if (rawPath.startsWith("http://") || rawPath.startsWith("https://")) {
            return rawPath;
        }
        Path p = Path.of(rawPath);
        // 绝对路径：直接校验是否存在
        if (p.isAbsolute()) {
            if (Files.exists(p)) return rawPath;
            return searchByFileName(p.getFileName().toString(), workspaceDir);
        }
        // 相对路径：先拼 workspace，不存在则按文件名搜索
        Path resolved = Path.of(workspaceDir).resolve(rawPath).normalize();
        if (Files.exists(resolved)) return resolved.toString();
        log.debug("路径不存在，尝试按文件名搜索: {} (已尝试: {})", rawPath, resolved);
        return searchByFileName(p.getFileName().toString(), workspaceDir);
    }

    /** 在 workspace 下递归搜索同名文件（最多 5 层），找到返回绝对路径，找不到返回 workspace 下的拼接路径 */
    private static String searchByFileName(String fileName, String workspaceDir) {
        try {
            var ws = Path.of(workspaceDir);
            try (var stream = Files.walk(ws, 5)) {
                var found = stream
                        .filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().equals(fileName))
                        .findFirst();
                if (found.isPresent()) {
                    String result = found.get().normalize().toString();
                    log.info("文件名搜索找到: {} -> {}", fileName, result);
                    return result;
                }
            }
        } catch (IOException ignored) {
            log.debug("文件名搜索异常: {}", ignored.getMessage());
        }
        // 最终兜底：拼到 workspace 下
        return Path.of(workspaceDir).resolve(fileName).normalize().toString();
    }
}