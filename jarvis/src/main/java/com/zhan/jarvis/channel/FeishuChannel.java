package com.zhan.jarvis.channel;

import com.lark.oapi.Client;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import com.zhan.jarvis.bus.InboundMessage;
import com.zhan.jarvis.bus.MessageBus;
import com.zhan.jarvis.bus.OutboundMessage;
import com.zhan.jarvis.config.JarvisConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 飞书 Channel：使用官方 SDK WebSocket 长连接收消息，不依赖公网 webhook。
 */
public class FeishuChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(FeishuChannel.class);
    private static final int MAX_DEDUP_SIZE = 1000;

    private final JarvisConfig.ChannelConfig.FeishuConfig config;
    private final MessageBus messageBus;
    private final ObjectMapper objectMapper;
    private final Set<String> allowFrom;
    private final Map<String, Boolean> processedMessageIds = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_DEDUP_SIZE;
                }
            });
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Client client;
    private com.lark.oapi.ws.Client wsClient;

    public FeishuChannel(JarvisConfig.ChannelConfig.FeishuConfig config,
                         MessageBus messageBus,
                         ObjectMapper objectMapper) {
        this.config = config;
        this.messageBus = messageBus;
        this.objectMapper = objectMapper;
        this.allowFrom = normalizeAllowFrom(config != null ? config.allowFrom() : null);
    }

    @Override
    public String type() {
        return "feishu";
    }

    @Override
    public void start() {
        if (config == null || !config.enabled()) {
            log.info("FeishuChannel 未启用");
            return;
        }
        if (isBlank(config.appId()) || isBlank(config.appSecret())) {
            log.warn("FeishuChannel 已启用但 app-id/app-secret 未配置，跳过启动");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }

        //初始化client
        this.client = Client.newBuilder(config.appId(), config.appSecret()).build();
        var dispatcher = EventDispatcher.newBuilder(valueOrEmpty(config.verificationToken()),
                        valueOrEmpty(config.encryptKey()))
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        handleIncoming(event);
                    }
                })
                .build();
        //初始化wsClient
        this.wsClient = new com.lark.oapi.ws.Client.Builder(config.appId(), config.appSecret())
                .eventHandler(dispatcher)
                .autoReconnect(true)
                .build();

        Thread.startVirtualThread(() -> {
            try {
                log.info("FeishuChannel WebSocket 长连接启动中: appId={}", config.appId());
                wsClient.start();
                log.info("FeishuChannel WebSocket 长连接启动成功: appId={}", config.appId());
            } catch (Exception e) {
                running.set(false);
                log.warn("FeishuChannel WebSocket 连接异常: {}", e.getMessage());
                log.debug("FeishuChannel WebSocket 异常详情", e);
            }
        });
    }

    @Override
    public void stop() {
        running.set(false);
        log.info("FeishuChannel 已标记停止。飞书 SDK 当前版本未暴露公开 close 方法，进程退出时释放连接");
    }

    @Override
    public void send(OutboundMessage message) {
        if (client == null) {
            log.warn("FeishuChannel 未初始化，无法发送 outbound: inboundId={}", message.inboundId());
            return;
        }
        if (isBlank(message.content())) {
            return;
        }

        try {
            var metadata = message.metadata();
            String replyTo = stringValue(metadata.get("reply_to"));
            String replyToMessageId = firstNonBlank(
                    stringValue(metadata.get("reply_to_message_id")),
                    stringValue(metadata.get("message_id"))
            );
            String chatType = firstNonBlank(stringValue(metadata.get("chat_type")), "group");
            String rootId = stringValue(metadata.get("root_id"));
            String content = message.content();

            if ("group".equals(chatType)) {
                String senderId = stringValue(metadata.get("sender_id"));
                if (!isBlank(senderId)) {
                    content = "<at user_id=\"" + senderId + "\"></at>\n" + content;
                }
            }

            String payload = objectMapper.writeValueAsString(Map.of("text", content));
            if (!isBlank(replyToMessageId)) {
                boolean replyInThread = !isBlank(rootId) && !rootId.equals(replyToMessageId);
                var request = ReplyMessageReq.newBuilder()
                        .messageId(replyToMessageId)
                        .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                                .msgType("text")
                                .content(payload)
                                .replyInThread(replyInThread)
                                .build())
                        .build();
                var response = client.im().v1().message().reply(request);
                if (!response.success()) {
                    log.warn("FeishuChannel 回复失败: code={}, msg={}, requestId={}",
                            response.getCode(), response.getMsg(), response.getRequestId());
                }
                return;
            }

            if (isBlank(replyTo)) {
                log.warn("FeishuChannel 缺少 reply_to，无法发送: inboundId={}", message.inboundId());
                return;
            }
            var receiveIdType = replyTo.startsWith("oc_") ? "chat_id" : "open_id";
            var request = CreateMessageReq.newBuilder()
                    .receiveIdType(receiveIdType)
                    .createMessageReqBody(CreateMessageReqBody.newBuilder()
                            .receiveId(replyTo)
                            .msgType("text")
                            .content(payload)
                            .build())
                    .build();
            var response = client.im().v1().message().create(request);
            if (!response.success()) {
                log.warn("FeishuChannel 发送失败: code={}, msg={}, requestId={}",
                        response.getCode(), response.getMsg(), response.getRequestId());
            }
        } catch (Exception e) {
            log.warn("FeishuChannel outbound 发送异常: inboundId={}, error={}", message.inboundId(), e.getMessage());
            log.debug("FeishuChannel outbound 发送异常详情", e);
        }
    }

    private void handleIncoming(P2MessageReceiveV1 event) {
        try {
            if (event == null || event.getEvent() == null) {
                return;
            }
            var data = event.getEvent();
            EventMessage message = data.getMessage();
            EventSender sender = data.getSender();
            if (message == null || sender == null) {
                return;
            }

            String messageId = message.getMessageId();
            if (isBlank(messageId) || alreadyProcessed(messageId)) {
                return;
            }
            if ("bot".equalsIgnoreCase(valueOrEmpty(sender.getSenderType()))) {
                return;
            }

            String senderId = sender.getSenderId() != null ? sender.getSenderId().getOpenId() : "";
            if (isBlank(senderId) || !isAllowed(senderId)) {
                log.warn("FeishuChannel 忽略未授权或未知 sender: senderId={}, messageId={}", senderId, messageId);
                return;
            }

            String messageType = valueOrEmpty(message.getMessageType());
            String content = parseContent(message, messageType);
            if (isBlank(content)) {
                return;
            }

            String chatType = valueOrEmpty(message.getChatType()).toLowerCase(Locale.ROOT);
            String chatId = valueOrEmpty(message.getChatId());
            boolean mentioned = isBotMentioned(message);
            if (!shouldProcess(chatType, mentioned)) {
                log.debug("FeishuChannel 忽略未 @ 的群消息: messageId={}", messageId);
                return;
            }

            content = removeBotMentionPlaceholders(content, message.getMentions()).strip();
            if ("group".equals(chatType)) {
                content = "[" + senderId + "]: " + content;
            }

            String replyTo = "group".equals(chatType) ? chatId : senderId;
            String finalChatId = chatId;
            String rootId = message.getRootId();
            if ("group".equals(chatType) && isThreadMessage(message)) {
                if (isBlank(rootId)) {
                    rootId = messageId;
                }
                finalChatId = replyTo + "#" + rootId;
            }

            var sessionKey = new SessionKey(type(), config.appId(), finalChatId);
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("message_id", messageId);
            metadata.put("chat_type", chatType);
            metadata.put("chat_id", chatId);
            metadata.put("reply_to", replyTo);
            metadata.put("msg_type", messageType);
            metadata.put("root_id", rootId);
            metadata.put("thread_id", message.getThreadId());
            metadata.put("sender_id", senderId);

            var inbound = InboundMessage.of(messageId, sessionKey, sessionKey.canonical(),
                    senderId, content, metadata);
            messageBus.submit(inbound);
            log.info("FeishuChannel inbound 已提交: messageId={}, sessionId={}", messageId, inbound.sessionId());
        } catch (Exception e) {
            log.warn("FeishuChannel 处理 inbound 异常: {}", e.getMessage());
            log.debug("FeishuChannel 处理 inbound 异常详情", e);
        }
    }

    private String parseContent(EventMessage message, String messageType) {
        if ("text".equals(messageType)) {
            try {
                var root = objectMapper.readTree(valueOrEmpty(message.getContent()));
                var text = root.get("text");
                return text != null ? text.asText("") : "";
            } catch (Exception e) {
                log.warn("FeishuChannel 文本内容解析失败: messageId={}, error={}",
                        message.getMessageId(), e.getMessage());
                return "";
            }
        }
        return switch (messageType) {
            case "image" -> "[image]";
            case "audio" -> "[audio]";
            case "file" -> "[file]";
            case "sticker" -> "[sticker]";
            case "post" -> "[post]";
            default -> "[" + messageType + "]";
        };
    }

    private boolean shouldProcess(String chatType, boolean mentioned) {
        if (!"group".equals(chatType)) {
            return true;
        }
        return !config.threadRequireMention() || mentioned;
    }

    private boolean isBotMentioned(EventMessage message) {
        String botName = valueOrEmpty(config.botName());
        MentionEvent[] mentions = message.getMentions();
        if (mentions == null || mentions.length == 0) {
            return false;
        }
        for (MentionEvent mention : mentions) {
            if (!isBlank(botName) && botName.equals(mention.getName())) {
                return true;
            }
        }
        return false;
    }

    private String removeBotMentionPlaceholders(String content, MentionEvent[] mentions) {
        if (mentions == null || mentions.length == 0 || isBlank(config.botName())) {
            return content;
        }
        String result = content;
        for (MentionEvent mention : mentions) {
            if (config.botName().equals(mention.getName()) && !isBlank(mention.getKey())) {
                result = result.replace(mention.getKey(), "");
            }
        }
        return result;
    }

    private boolean isThreadMessage(EventMessage message) {
        return !isBlank(message.getRootId()) || !isBlank(message.getThreadId());
    }

    private boolean alreadyProcessed(String messageId) {
        synchronized (processedMessageIds) {
            if (processedMessageIds.containsKey(messageId)) {
                return true;
            }
            processedMessageIds.put(messageId, Boolean.TRUE);
            return false;
        }
    }

    private boolean isAllowed(String senderId) {
        return allowFrom.isEmpty() || allowFrom.contains(senderId);
    }

    private static Set<String> normalizeAllowFrom(String[] values) {
        var result = new LinkedHashSet<String>();
        if (values == null) {
            return result;
        }
        Arrays.stream(values)
                .filter(v -> v != null && !v.isBlank())
                .map(String::strip)
                .forEach(result::add);
        return result;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonBlank(String first, String fallback) {
        return isBlank(first) ? fallback : first;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
