package com.zhan.jarvis.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能加载器 — 对标 Python SkillsLoader。
 *
 * 扫描 workspace/skills/ 目录，解析 SKILL.md 的 YAML frontmatter + vikingbot metadata，
 * 实现渐进式加载：
 *   1. always=true 的技能 → 全量注入 system prompt
 *   2. 其他技能 → 只注入摘要 XML（agent 按需 read_file 加载）
 *
 * SKILL.md 格式:
 * <pre>
 * ---
 * name: skill-name
 * description: What this skill does
 * metadata: {"vikingbot":{"always":true,"requires":{"bins":["tmux"],"env":["API_KEY"]}}}
 * ---
 *
 * # Skill content (markdown)
 * </pre>
 */
public class SkillsLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillsLoader.class);

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n?(.*)", Pattern.DOTALL);

    private final Path skillsDir;
    private final ObjectMapper json;

    public SkillsLoader(Path workspaceDir, ObjectMapper json) {
        this.skillsDir = workspaceDir.resolve("skills");
        this.json = json;
    }

    /**
     * 列出所有可用技能（过滤不满足依赖的）。
     */
    public List<SkillInfo> listSkills() {
        if (!Files.isDirectory(skillsDir)) return List.of();

        var skills = new ArrayList<SkillInfo>();
        try (var dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory).forEach(skillDir -> {
                Path skillFile = skillDir.resolve("SKILL.md");
                if (Files.exists(skillFile)) {
                    String name = skillDir.getFileName().toString();
                    String content = readFile(skillFile);
                    if (content == null) return;

                    SkillFrontMatter fm = parseFrontMatter(content);
                    SkillMeta meta = parseSkillMeta(fm);
                    boolean available = checkRequirements(meta);
                    skills.add(new SkillInfo(
                            name,
                            skillFile.toAbsolutePath().toString(),
                            fm.description() != null && !fm.description().isBlank() ? fm.description() : name,
                            available
                    ));
                }
            });
        } catch (IOException e) {
            log.warn("扫描 skills 目录失败: {}", skillsDir, e);
        }

        skills.sort(Comparator.comparing(SkillInfo::name));
        return List.copyOf(skills);
    }

    /**
     * 获取 always=true 且满足依赖的技能名称列表。
     */
    public List<String> getAlwaysSkills() {
        return listSkills().stream()
                .filter(info -> {
                    var meta = getSkillMeta(info.name());
                    return meta.always() && info.available();
                })
                .map(SkillInfo::name)
                .toList();
    }

    /**
     * 加载指定技能的内容（去掉 frontmatter）。
     */
    public String loadSkill(String name) {
        Path skillFile = skillsDir.resolve(name).resolve("SKILL.md");
        if (!Files.exists(skillFile)) return null;
        String content = readFile(skillFile);
        return content != null ? stripFrontMatter(content) : null;
    }

    /**
     * 加载多个技能用于注入上下文。
     */
    public String loadSkillsForContext(List<String> names) {
        if (names.isEmpty()) return "";

        var parts = new ArrayList<String>();
        for (String name : names) {
            String content = loadSkill(name);
            if (content != null && !content.isBlank()) {
                parts.add("### Skill: " + name + "\n\n" + content);
            }
        }
        if (parts.isEmpty()) return "";

        return String.join("\n\n---\n\n", parts);
    }

    /**
     * 构建技能摘要 XML（用于渐进式加载）。
     * agent 需要时通过 read_file 加载完整内容。
     */
    public String buildSkillsSummary() {
        var skills = listSkills();
        if (skills.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("<skills>");
        boolean appended = false;
        for (var info : skills) {
            var meta = getSkillMeta(info.name());
            if (!info.available() || meta.always()) {
                continue;
            }
            sb.append("\n  <skill available=\"").append(info.available()).append("\">");
            sb.append("\n    <name>").append(escapeXml(info.name())).append("</name>");
            sb.append("\n    <description>").append(escapeXml(info.description())).append("</description>");
            sb.append("\n    <location>").append(info.path()).append("</location>");
            sb.append("\n  </skill>");
            appended = true;
        }
        sb.append("\n</skills>");
        return appended ? sb.toString() : "";
    }

    // ---- internal ----

    private String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            log.warn("读取技能文件失败: {}", path, e);
            return null;
        }
    }

    /**
     * 解析 SKILL.md 的 YAML frontmatter。
     * 简单行解析（对标 Python 的 get_skill_metadata）。
     */
    SkillFrontMatter parseFrontMatter(String content) {
        if (content == null || !content.startsWith("---")) return SkillFrontMatter.EMPTY;

        Matcher m = FRONTMATTER_PATTERN.matcher(content);
        if (!m.find()) return SkillFrontMatter.EMPTY;

        String raw = m.group(1);
        var map = new LinkedHashMap<String, String>();
        var requiresBins = new ArrayList<String>();
        var requiresEnv = new ArrayList<String>();
        String section = "";
        String requiresTarget = "";

        for (String line : raw.split("\n")) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            String stripped = line.strip();

            if (indent == 0 && stripped.endsWith(":")) {
                section = stripped.substring(0, stripped.length() - 1).trim();
                continue;
            }

            if ("requires".equals(section)) {
                requiresTarget = parseRequiresLine(stripped, requiresTarget, requiresBins, requiresEnv);
                continue;
            }

            int colonIdx = stripped.indexOf(':');
            if (colonIdx > 0) {
                String key = stripped.substring(0, colonIdx).trim();
                String value = stripQuotes(stripped.substring(colonIdx + 1).trim());
                map.put(key, value);
                section = key;
                requiresTarget = "";

                if ("requires.bins".equals(key)) {
                    requiresBins.addAll(parseYamlList(value));
                } else if ("requires.env".equals(key)) {
                    requiresEnv.addAll(parseYamlList(value));
                }
            }
        }

        return new SkillFrontMatter(
                map.getOrDefault("name", ""),
                map.getOrDefault("description", ""),
                map.getOrDefault("metadata", ""),
                map.getOrDefault("always", ""),
                List.copyOf(requiresBins),
                List.copyOf(requiresEnv)
        );
    }

    /**
     * 去掉 content 中的 YAML frontmatter。
     */
    String stripFrontMatter(String content) {
        if (content == null || !content.startsWith("---")) return content;
        Matcher m = FRONTMATTER_PATTERN.matcher(content);
        if (m.find()) {
            return m.group(2) != null ? m.group(2).trim() : "";
        }
        return content;
    }

    /**
     * 从 frontmatter.metadata JSON 中解析 vikingbot 配置。
     */
    SkillMeta parseSkillMeta(String metadata) {
        if (metadata == null || metadata.isBlank()) return SkillMeta.EMPTY;

        try {
            var root = json.readTree(metadata);
            var vb = root.get("vikingbot");
            if (vb == null) return SkillMeta.EMPTY;

            String emoji = vb.has("emoji") ? vb.get("emoji").asText() : "";
            boolean always = vb.has("always") && vb.get("always").asBoolean();

            var requires = vb.get("requires");
            List<String> bins = List.of();
            List<String> env = List.of();
            if (requires != null) {
                bins = jsonArrayToList(requires.get("bins"));
                env = jsonArrayToList(requires.get("env"));
            }

            return new SkillMeta(emoji, List.of(), always, new SkillMeta.Requires(bins, env));
        } catch (Exception e) {
            log.debug("解析 skill metadata 失败: {}", metadata, e);
            return SkillMeta.EMPTY;
        }
    }

    SkillMeta parseSkillMeta(SkillFrontMatter fm) {
        SkillMeta fromMetadata = parseSkillMeta(fm.metadata());
        boolean always = fromMetadata.always();
        if (fm.always() != null && !fm.always().isBlank()) {
            always = Boolean.parseBoolean(fm.always());
        }

        var bins = new ArrayList<>(fromMetadata.requires().bins());
        var env = new ArrayList<>(fromMetadata.requires().env());
        bins.addAll(fm.requiresBins());
        env.addAll(fm.requiresEnv());

        return new SkillMeta(
                fromMetadata.emoji(),
                fromMetadata.os(),
                always,
                new SkillMeta.Requires(distinct(bins), distinct(env))
        );
    }

    private List<String> jsonArrayToList(tools.jackson.databind.JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        var list = new ArrayList<String>();
        for (var item : node) {
            list.add(item.asText());
        }
        return List.copyOf(list);
    }

    /**
     * 检查技能依赖是否满足（bins 在 PATH、env vars 已设置）。
     */
    boolean checkRequirements(SkillMeta meta) {
        for (String bin : meta.requires().bins()) {
            if (!isOnPath(bin)) return false;
        }
        for (String env : meta.requires().env()) {
            if (System.getenv(env) == null) return false;
        }
        return true;
    }

    private boolean isOnPath(String cmd) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return false;
        return Arrays.stream(pathEnv.split(java.io.File.pathSeparator))
                .map(Path::of)
                .anyMatch(dir -> {
                    Path exe = dir.resolve(cmd);
                    return Files.isExecutable(exe);
                });
    }

    private String getMissingRequirements(SkillMeta meta) {
        var missing = new ArrayList<String>();
        for (String bin : meta.requires().bins()) {
            if (!isOnPath(bin)) missing.add("CLI: " + bin);
        }
        for (String env : meta.requires().env()) {
            if (System.getenv(env) == null) missing.add("ENV: " + env);
        }
        return String.join(", ", missing);
    }

    private SkillMeta getSkillMeta(String name) {
        Path skillFile = skillsDir.resolve(name).resolve("SKILL.md");
        if (!Files.exists(skillFile)) return SkillMeta.EMPTY;
        String content = readFile(skillFile);
        if (content == null) return SkillMeta.EMPTY;
        SkillFrontMatter fm = parseFrontMatter(content);
        return parseSkillMeta(fm);
    }

    private static String parseRequiresLine(String stripped, String target, List<String> bins, List<String> env) {
        if (stripped.startsWith("bins:")) {
            String value = stripped.substring("bins:".length()).trim();
            bins.addAll(parseYamlList(value));
            return "bins";
        } else if (stripped.startsWith("env:")) {
            String value = stripped.substring("env:".length()).trim();
            env.addAll(parseYamlList(value));
            return "env";
        } else if (stripped.startsWith("- ")) {
            if ("bins".equals(target)) {
                bins.add(stripQuotes(stripped.substring(2).trim()));
            } else if ("env".equals(target)) {
                env.add(stripQuotes(stripped.substring(2).trim()));
            }
        }
        return target;
    }

    private static List<String> parseYamlList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String value = raw.strip();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
            return Arrays.stream(value.split(","))
                    .map(String::trim)
                    .map(SkillsLoader::stripQuotes)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        if (value.startsWith("- ")) {
            return List.of(stripQuotes(value.substring(2).trim()));
        }
        return List.of(stripQuotes(value));
    }

    private static String stripQuotes(String value) {
        if (value == null) return "";
        String stripped = value.strip();
        if ((stripped.startsWith("\"") && stripped.endsWith("\""))
                || (stripped.startsWith("'") && stripped.endsWith("'"))) {
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    private static List<String> distinct(List<String> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
