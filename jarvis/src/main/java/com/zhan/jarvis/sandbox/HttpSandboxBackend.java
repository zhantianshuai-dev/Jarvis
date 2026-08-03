package com.zhan.jarvis.sandbox;

import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 沙箱后端。
 * Jarvis 只负责路径映射，实际文件和命令操作由独立 sandbox-service 完成。
 */
public class HttpSandboxBackend implements SandboxBackend {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private final WebClient webClient;
    private final Path hostRoot;
    private final Path sandboxRoot;

    public HttpSandboxBackend(WebClient.Builder builder, String baseUrl, String hostRoot, String sandboxRoot) {
        this.webClient = builder.baseUrl(trimTrailingSlash(baseUrl)).build();
        this.hostRoot = Path.of(valueOrDefault(hostRoot, ".")).toAbsolutePath().normalize();
        this.sandboxRoot = Path.of(valueOrDefault(sandboxRoot, "/workspace")).toAbsolutePath().normalize();
    }

    @Override
    public CommandResult execute(String command, Path workspaceDir) throws IOException {
        Map<String, Object> response = post("/api/v1/sandbox/exec", Map.of(
                "workspace", toSandboxWorkspace(workspaceDir),
                "command", command
        ));
        ensureSuccess(response);
        return new CommandResult(
                intValue(response.get("exitCode"), -1),
                stringValue(response.get("output")),
                booleanValue(response.get("timedOut")),
                longValue(response.get("timeoutSeconds"), 60)
        );
    }

    @Override
    public String readFile(Path workspaceDir, String path) throws IOException {
        Map<String, Object> response = post("/api/v1/sandbox/read", Map.of(
                "workspace", toSandboxWorkspace(workspaceDir),
                "path", path
        ));
        ensureSuccess(response);
        return stringValue(response.get("content"));
    }

    @Override
    public Path writeFile(Path workspaceDir, String path, String content) throws IOException {
        var request = new LinkedHashMap<String, Object>();
        request.put("workspace", toSandboxWorkspace(workspaceDir));
        request.put("path", path);
        request.put("content", content == null ? "" : content);
        Map<String, Object> response = post("/api/v1/sandbox/write", request);
        ensureSuccess(response);
        return toHostPath(stringValue(response.get("path")));
    }

    @Override
    public List<Path> listDir(Path workspaceDir, String path) throws IOException {
        Map<String, Object> response = post("/api/v1/sandbox/list", Map.of(
                "workspace", toSandboxWorkspace(workspaceDir),
                "path", path
        ));
        ensureSuccess(response);
        Object rawItems = response.get("items");
        if (!(rawItems instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .map(item -> toHostPath(String.valueOf(item)))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body) throws IOException {
        try {
            return webClient.post()
                    .uri(path)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(REQUEST_TIMEOUT);
        } catch (Exception e) {
            throw new IOException("调用 sandbox-service 失败: " + e.getMessage(), e);
        }
    }

    private String toSandboxWorkspace(Path workspaceDir) throws IOException {
        Path workspace = workspaceDir.toAbsolutePath().normalize();
        if (!workspace.startsWith(hostRoot)) {
            throw new IOException("workspace 不在 sandbox host-root 下: " + workspace);
        }
        Path relative = hostRoot.relativize(workspace);
        Path mapped = sandboxRoot.resolve(relative).normalize();
        if (!mapped.startsWith(sandboxRoot)) {
            throw new IOException("sandbox workspace 映射越界: " + mapped);
        }
        return mapped.toString();
    }

    private Path toHostPath(String sandboxPath) {
        Path resolved = Path.of(sandboxPath).toAbsolutePath().normalize();
        if (!resolved.startsWith(sandboxRoot)) {
            return hostRoot;
        }
        return hostRoot.resolve(sandboxRoot.relativize(resolved)).normalize();
    }

    private static void ensureSuccess(Map<String, Object> response) throws IOException {
        if (response == null) {
            throw new IOException("sandbox-service 返回为空");
        }
        if (!booleanValue(response.get("success"))) {
            throw new IOException(stringValue(response.get("error")));
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String trimTrailingSlash(String value) {
        String base = valueOrDefault(value, "http://localhost:8090");
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }
}
