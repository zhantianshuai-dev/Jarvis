package com.zhan.jarvis.tool.impl;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.ListMessageReq;
import com.zhan.jarvis.config.JarvisConfig;
import com.zhan.jarvis.tool.McpTool;
import com.zhan.jarvis.tool.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读取当前飞书会话的历史消息。
 * 不接受外部 chat_id，避免 LLM 越权读取其他会话。
 */
public class FeishuHistoryMessagesTool implements McpTool {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_CONTENT_CHARS = 2000;

    private final ObjectMapper objectMapper;
    private final JarvisConfig.ChannelConfig.FeishuConfig config;
    private final Client client;

    public FeishuHistoryMessagesTool(ObjectMapper objectMapper,
                                     JarvisConfig.ChannelConfig.FeishuConfig config) {
        this.objectMapper = objectMapper;
        this.config = config;
        this.client = usable(config) ? Client.newBuilder(config.appId(), config.appSecret()).build() : null;
    }

    @Override
    public String name() {
        return "feishu_history_messages";
    }

    @Override
    public String description() {
        return "读取当前飞书单聊、群聊或话题的历史消息。只能读取当前触发 Agent 的飞书会话，适合总结群聊上下文或回顾最近讨论。";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = objectMapper.createObjectNode().put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("container_id_type")
                .put("type", "string")
                .put("description", "读取范围：chat 表示当前单聊/群聊，thread 表示当前话题。默认 chat。")
                .putArray("enum").add("chat").add("thread");
        props.putObject("page_size")
                .put("type", "integer")
                .put("minimum", 1)
                .put("maximum", MAX_PAGE_SIZE)
                .put("description", "返回消息数量，默认 20，最大 50。");
        props.putObject("start_time")
                .put("type", "string")
                .put("description", "可选，查询起始时间，秒级时间戳。thread 范围不支持。");
        props.putObject("end_time")
                .put("type", "string")
                .put("description", "可选，查询结束时间，秒级时间戳。thread 范围不支持。");
        props.putObject("sort_type")
                .put("type", "string")
                .put("description", "排序方式，默认 ByCreateTimeDesc。")
                .putArray("enum").add("ByCreateTimeAsc").add("ByCreateTimeDesc");
        props.putObject("page_token")
                .put("type", "string")
                .put("description", "可选，分页 token。");
        props.putObject("include_current_message")
                .put("type", "boolean")
                .put("description", "是否包含触发本次 Agent 的当前消息，默认 false。");
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        if (client == null) {
            return toJson(Map.of("error", "飞书历史消息工具未配置：请启用 feishu channel 并配置 app-id/app-secret"));
        }
        if (ctx == null || ctx.sessionKey() == null || !"feishu".equals(ctx.sessionKey().channelType())) {
            return toJson(Map.of("error", "feishu_history_messages 只能在飞书会话中调用"));
        }

        String containerType = stringArg(arguments, "container_id_type", "chat");
        if (!"chat".equals(containerType) && !"thread".equals(containerType)) {
            return toJson(Map.of("error", "container_id_type 只支持 chat 或 thread"));
        }

        String containerId = resolveContainerId(ctx, containerType);
        if (isBlank(containerId)) {
            return toJson(Map.of(
                    "error", "无法确定当前飞书会话 ID",
                    "container_id_type", containerType
            ));
        }

        int pageSize = intArg(arguments, "page_size", DEFAULT_PAGE_SIZE);
        pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
        String sortType = stringArg(arguments, "sort_type", "ByCreateTimeDesc");
        if (!"ByCreateTimeAsc".equals(sortType) && !"ByCreateTimeDesc".equals(sortType)) {
            sortType = "ByCreateTimeDesc";
        }

        try {
            var builder = ListMessageReq.newBuilder()
                    .containerIdType(containerType)
                    .containerId(containerId)
                    .sortType(sortType)
                    .pageSize(pageSize);
            putIfPresent(builder, "start_time", arguments);
            putIfPresent(builder, "end_time", arguments);
            String pageToken = stringArg(arguments, "page_token", "");
            if (!isBlank(pageToken)) {
                builder.pageToken(pageToken);
            }

            var response = client.im().v1().message().list(builder.build());
            if (!response.success()) {
                return toJson(Map.of(
                        "error", "飞书历史消息读取失败",
                        "code", response.getCode(),
                        "msg", valueOrEmpty(response.getMsg()),
                        "request_id", valueOrEmpty(response.getRequestId())
                ));
            }

            var data = response.getData();
            var items = data != null && data.getItems() != null ? data.getItems() : new com.lark.oapi.service.im.v1.model.Message[0];
            boolean includeCurrent = boolArg(arguments, "include_current_message", false);
            String currentMessageId = stringValue(ctx.metadata().get("message_id"));
            var messages = new ArrayList<Map<String, Object>>();
            for (var item : items) {
                if (!includeCurrent && !isBlank(currentMessageId) && currentMessageId.equals(item.getMessageId())) {
                    continue;
                }
                messages.add(toMessageMap(item));
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("container_id_type", containerType);
            result.put("container_id", containerId);
            result.put("sort_type", sortType);
            result.put("has_more", data != null && Boolean.TRUE.equals(data.getHasMore()));
            result.put("page_token", data != null ? valueOrEmpty(data.getPageToken()) : "");
            result.put("count", messages.size());
            result.put("messages", messages);
            return toJson(result);
        } catch (Exception e) {
            return toJson(Map.of(
                    "error", "飞书历史消息读取异常",
                    "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
            ));
        }
    }

    private String resolveContainerId(ToolContext ctx, String containerType) {
        if ("thread".equals(containerType)) {
            return firstNonBlank(
                    stringValue(ctx.metadata().get("thread_id")),
                    stringValue(ctx.metadata().get("root_id"))
            );
        }
        return firstNonBlank(
                stringValue(ctx.metadata().get("chat_id")),
                ctx.sessionKey() != null ? ctx.sessionKey().chatId() : ""
        );
    }

    private Map<String, Object> toMessageMap(com.lark.oapi.service.im.v1.model.Message message) {
        var result = new LinkedHashMap<String, Object>();
        result.put("message_id", valueOrEmpty(message.getMessageId()));
        result.put("root_id", valueOrEmpty(message.getRootId()));
        result.put("parent_id", valueOrEmpty(message.getParentId()));
        result.put("thread_id", valueOrEmpty(message.getThreadId()));
        result.put("chat_id", valueOrEmpty(message.getChatId()));
        result.put("msg_type", valueOrEmpty(message.getMsgType()));
        result.put("create_time", valueOrEmpty(message.getCreateTime()));
        result.put("updated", Boolean.TRUE.equals(message.getUpdated()));
        result.put("deleted", Boolean.TRUE.equals(message.getDeleted()));

        var sender = message.getSender();
        if (sender != null) {
            result.put("sender_id", valueOrEmpty(sender.getId()));
            result.put("sender_id_type", valueOrEmpty(sender.getIdType()));
            result.put("sender_type", valueOrEmpty(sender.getSenderType()));
        }

        String rawContent = message.getBody() != null ? valueOrEmpty(message.getBody().getContent()) : "";
        result.put("text", extractText(rawContent));
        result.put("content", truncate(rawContent, MAX_CONTENT_CHARS));
        return result;
    }

    private String extractText(String rawContent) {
        if (isBlank(rawContent)) {
            return "";
        }
        try {
            var root = objectMapper.readTree(rawContent);
            var text = root.get("text");
            if (text != null) {
                return truncate(text.asText(""), MAX_CONTENT_CHARS);
            }
            var title = root.get("title");
            if (title != null) {
                return truncate(title.asText(""), MAX_CONTENT_CHARS);
            }
        } catch (Exception ignored) {
            // 非 JSON 内容直接返回原文截断。
        }
        return truncate(rawContent, MAX_CONTENT_CHARS);
    }

    private void putIfPresent(ListMessageReq.Builder builder, String key, Map<String, Object> arguments) {
        String value = stringArg(arguments, key, "");
        if (isBlank(value)) {
            return;
        }
        if ("start_time".equals(key)) {
            builder.startTime(value);
        } else if ("end_time".equals(key)) {
            builder.endTime(value);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"JSON 序列化失败\"}";
        }
    }

    private static boolean usable(JarvisConfig.ChannelConfig.FeishuConfig config) {
        return config != null && config.enabled()
                && !isBlank(config.appId()) && !isBlank(config.appSecret());
    }

    private static String stringArg(Map<String, Object> args, String key, String fallback) {
        if (args == null) {
            return fallback;
        }
        Object value = args.get(key);
        return value == null ? fallback : value.toString().strip();
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        if (args == null) {
            return fallback;
        }
        Object value = args.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean boolArg(Map<String, Object> args, String key, boolean fallback) {
        if (args == null) {
            return fallback;
        }
        Object value = args.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
