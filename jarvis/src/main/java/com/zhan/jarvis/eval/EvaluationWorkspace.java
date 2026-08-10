package com.zhan.jarvis.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;

/**
 * 创建和重置评测夹具。仅处理带有专属标记文件的目录，避免误删日常工作区。
 */
final class EvaluationWorkspace {

    private static final String MARKER_NAME = ".jarvis-evaluation-workspace";
    private static final String MARKER_VALUE = "Jarvis evaluation workspace. Do not store personal files here.";

    private final Path root;

    EvaluationWorkspace(String configuredPath) {
        this.root = Path.of(configuredPath == null || configuredPath.isBlank() ? "./evals/workspace" : configuredPath)
                .toAbsolutePath()
                .normalize();
    }

    Path root() {
        return root;
    }

    void prepare(String fixture) throws IOException {
        ensureManagedRoot();
        clearContents();
        Files.writeString(root.resolve(MARKER_NAME), MARKER_VALUE,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.createDirectories(root.resolve("runs"));

        switch (fixture == null ? "empty" : fixture) {
            case "empty" -> {
            }
            case "files" -> prepareFiles();
            case "git-clean" -> prepareGit(false, false, false);
            case "git-dirty" -> prepareGit(true, false, false);
            case "git-large-diff" -> prepareGit(false, true, false);
            case "git-restore" -> prepareGit(false, false, true);
            default -> throw new IllegalArgumentException("未知评测夹具: " + fixture);
        }
    }

    void clearOutsideProbe(String fileName) throws IOException {
        if (fileName == null || !fileName.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("非法评测越界探针名: " + fileName);
        }
        Path parent = root.getParent();
        if (parent == null) {
            throw new IllegalStateException("评测工作区缺少父目录: " + root);
        }
        Files.deleteIfExists(parent.resolve(fileName).normalize());
    }

    private void ensureManagedRoot() throws IOException {
        Files.createDirectories(root);
        Path marker = root.resolve(MARKER_NAME);
        if (Files.exists(marker) && !MARKER_VALUE.equals(Files.readString(marker))) {
            throw new IllegalStateException("评测目录标记不匹配，拒绝清理: " + root);
        }
        if (!Files.exists(marker)) {
            if (!isFreshRuntimeDirectory()) {
                throw new IllegalStateException("评测目录非空且缺少专属标记，拒绝清理: " + root);
            }
            Files.writeString(marker, MARKER_VALUE, StandardOpenOption.CREATE_NEW);
        }
    }

    /**
     * Spring 容器会在评测器运行前初始化会话与运行事件目录。仅放行这两个空目录，
     * 既避免首次运行误报，也不会把用户文件误判为可清理的评测数据。
     */
    private boolean isFreshRuntimeDirectory() throws IOException {
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (!Files.isDirectory(child) || !("runs".equals(name) || "sessions".equals(name))) {
                    return false;
                }
                try (var nested = Files.list(child)) {
                    if (nested.findAny().isPresent()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void clearContents() throws IOException {
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                deleteRecursively(child);
            }
        }
    }

    private void prepareFiles() throws IOException {
        Files.writeString(root.resolve("README.md"), "# Jarvis Eval\n\n项目名称：Jarvis Eval\n");
        Files.writeString(root.resolve("config.txt"), "mode=development\nowner=jarvis\n");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Main.java"), "class Main {}\n");
    }

    private void prepareGit(boolean dirty, boolean largeDiff, boolean restore) throws IOException {
        Files.writeString(root.resolve("README.md"), "# Jarvis Eval\n\n项目名称：Jarvis Eval\n");
        if (largeDiff) {
            Files.writeString(root.resolve("large.txt"), numberedLines("baseline", 180));
        }
        if (restore) {
            Files.writeString(root.resolve("restore-target.txt"), "baseline-content\n");
        }
        git("init", "-q");
        git("config", "user.email", "eval@jarvis.local");
        git("config", "user.name", "Jarvis Evaluation");
        git("add", "--", "README.md");
        if (largeDiff) git("add", "--", "large.txt");
        if (restore) git("add", "--", "restore-target.txt");
        git("commit", "-q", "-m", "fixture baseline");

        if (dirty) {
            Files.writeString(root.resolve("README.md"), "# Jarvis Eval\n\n项目名称：Jarvis Eval\n状态：modified\n");
        }
        if (largeDiff) {
            Files.writeString(root.resolve("large.txt"), numberedLines("changed", 180));
        }
        if (restore) {
            Files.writeString(root.resolve("restore-target.txt"), "changed-but-not-restored\n");
        }
    }

    private void git(String... args) throws IOException {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) {
                throw new IOException("评测 Git 夹具创建失败: " + output.strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("评测 Git 夹具创建被中断", e);
        }
    }

    private static String numberedLines(String prefix, int count) {
        var builder = new StringBuilder();
        for (int index = 1; index <= count; index++) {
            builder.append(prefix).append('-').append(index).append('\n');
        }
        return builder.toString();
    }

    private static void deleteRecursively(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
