package com.zhan.memoryservice.session;

import com.zhan.memoryservice.llm.LLMProvider;
import com.zhan.common.llm.PromptManager;
import com.zhan.memoryservice.model.ContextType;
import com.zhan.memoryservice.model.WriteRequest;
import com.zhan.memoryservice.service.ContentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话服务，负责消息写入、自动归档、工作记忆生成和长期记忆提取。
 *
 * <pre>
 * 正常流程:
 *   createSession → addMessage → addMessage → ...（pending_tokens 累积）
 *   → 自动 commit（超过阈值）或手动 commit
 *     → 阶段 1（同步）：切分消息、保留最近 N 条、归档旧的
 *     → 阶段 2（后台虚拟线程）：LLM 生成工作记忆 → 记忆提取 → 去重 → 写入
 * </pre>
 */
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    // 待归档 token 超过此阈值自动触发提交归档。
    private static final int AUTO_COMMIT_TOKEN_THRESHOLD = 8000;

    private final SessionStore store;
    private final MemoryExtractor extractor;
    private final MemoryDeduplicator deduplicator;
    private final ContentService contentService;
    private final LLMProvider llm;
    private final PromptManager prompts;
    private final MemoryExtractionInputFilter memoryInputFilter = new MemoryExtractionInputFilter();
    private final SessionMessageVisibilityFilter visibilityFilter = new SessionMessageVisibilityFilter();

    public SessionService(SessionStore store, MemoryExtractor extractor,
                           MemoryDeduplicator deduplicator, ContentService contentService,
                           LLMProvider llm, PromptManager prompts) {
        this.store = store;
        this.extractor = extractor;
        this.deduplicator = deduplicator;
        this.contentService = contentService;
        this.llm = llm;
        this.prompts = prompts;
    }

    // ---- 会话生命周期 ----

    /** 创建或获取会话 */
    public Session createSession(String sessionId) {
        return store.createOrGet(sessionId, 10);
    }

    /** 创建或获取指定归属用户的会话 */
    public Session createSession(String sessionId, String ownerUserId) {
        var session = store.createOrGet(sessionId, 10);
        if ((session.ownerUserId() == null || session.ownerUserId().isBlank())
                && ownerUserId != null && !ownerUserId.isBlank()) {
            session = session.withOwner(ownerUserId);
            store.update(session);
        }
        return session;
    }

    /** 添加消息 */
    public Message addMessage(String sessionId, String role, String text) {
        return addMessage(sessionId, role, text, Map.of());
    }

    /** 添加消息，支持可选元数据写入 JSONL。 */
    public Message addMessage(String sessionId, String role, String text, Map<String, Object> metadata) {
        var session = store.get(sessionId);
        var msg = Message.of(role, text, null, metadata);
        store.addMessage(sessionId, msg);

        // 更新待归档 token 数
        int msgTokens = msg.estimatedTokens();
        int newPending = session.pendingTokens();
        if (session.keepRecentCount() <= 0) {
            newPending += msgTokens;
        } else if (session.messageCount() + 1 > session.keepRecentCount()) {
            // 简化滑动窗口：超出 keep_recent_count 的部分计入待归档 token。
            newPending += msgTokens;
        }

        String title = session.title();
        if ((title == null || title.isBlank()) && "user".equals(role)) {
            title = deriveTitle(text);
        }

        var updated = new Session(session.sessionId(), session.messageCount() + 1,
                "user".equals(role) ? session.totalTurns() + 1 : session.totalTurns(),
                session.compressionCount(), session.keepRecentCount(),
                newPending, session.ownerUserId(), title, session.createdAt(), Instant.now());
        store.update(updated);

        // 自动提交归档
        if (newPending >= AUTO_COMMIT_TOKEN_THRESHOLD) {
            log.info("pending_tokens={}, 触发自动 commit", newPending);
            commitAsync(sessionId);
        }

        return msg;
    }

    // ---- 提交归档 ----

    /**
     * 同步归档 + 后台记忆提取。
     * 阶段 1 同步执行后立即返回，阶段 2 在虚拟线程中运行。
     */
    public Map<String, Object> commitAsync(String sessionId) {
        var session = store.get(sessionId);
        var allMessages = store.getMessages(sessionId);
        if (allMessages.isEmpty()) {
            return Map.of("session_id", sessionId, "archived", false, "reason", "no_messages");
        }

        int keep = session.keepRecentCount();
        List<Message> toArchive;
        List<String> archiveIds;

        //归档历史对话
        if (keep > 0 && allMessages.size() > keep) {
            int splitIdx = allMessages.size() - keep;
            toArchive = allMessages.subList(0, splitIdx);
            archiveIds = toArchive.stream().map(Message::id).toList();
            store.deleteMessages(sessionId, archiveIds);
        } else if (keep <= 0) {
            toArchive = List.copyOf(allMessages);
            archiveIds = toArchive.stream().map(Message::id).toList();
            store.deleteMessages(sessionId, archiveIds);
        } else {
            return Map.of("session_id", sessionId, "archived", false, "reason", "all_within_keep_window");
        }

        // 更新会话元数据
        int remainingCount = store.messageCount(sessionId);
        var updated = new Session(session.sessionId(), remainingCount, session.totalTurns(),
                session.compressionCount() + 1, session.keepRecentCount(), 0,
                session.ownerUserId(), session.title(), session.createdAt(), Instant.now());
        store.update(updated);

        int count = updated.compressionCount();
        log.info("归档完成: archive_{}, {} 条消息", String.format("%03d", count), toArchive.size());

        // 阶段 2：后台记忆提取
        var msgs = List.copyOf(toArchive); // 捕获用于异步
        Thread.startVirtualThread(() -> runMemoryExtraction(sessionId, count, msgs));

        return Map.of("session_id", sessionId, "archived", true,
                "archive_id", String.format("archive_%03d", count),
                "archived_count", toArchive.size());
    }

    /** 手动提交归档（指定保留最近 N 条） */
    public Map<String, Object> commit(String sessionId, int keepRecentCount) {
        var session = store.get(sessionId);
        // 更新最近消息保留数量。
        var tmp = new Session(session.sessionId(), session.messageCount(), session.totalTurns(),
                session.compressionCount(), keepRecentCount, session.pendingTokens(),
                session.ownerUserId(), session.title(), session.createdAt(), session.updatedAt());
        store.update(tmp);
        return commitAsync(sessionId);
    }

    public List<Map<String, Object>> listSessions(String ownerUserId) {
        return store.listSessions().stream()
                .filter(s -> ownerUserId == null || ownerUserId.isBlank()
                        || ownerUserId.equals(s.ownerUserId()))
                .map(s -> {
                    var item = new LinkedHashMap<String, Object>();
                    item.put("session_id", s.sessionId());
                    item.put("owner_user_id", s.ownerUserId() != null ? s.ownerUserId() : "");
                    item.put("title", s.title() != null && !s.title().isBlank() ? s.title() : "新的对话");
                    item.put("message_count", s.messageCount());
                    item.put("total_turns", s.totalTurns());
                    item.put("compression_count", s.compressionCount());
                    item.put("created_at", s.createdAt().toString());
                    item.put("updated_at", s.updatedAt().toString());
                    return (Map<String, Object>) item;
                })
                .toList();
    }

    public Map<String, Object> getSessionMessages(String sessionId, int maxMessages) {
        var session = store.get(sessionId);
        List<Message> source = maxMessages > 0
                ? store.getRecentMessages(sessionId, maxMessages)
                : store.getMessages(sessionId);
        var result = new LinkedHashMap<String, Object>();
        result.put("session_id", sessionId);
        result.put("owner_user_id", session.ownerUserId() != null ? session.ownerUserId() : "");
        result.put("title", session.title() != null && !session.title().isBlank() ? session.title() : "新的对话");
        result.put("tool_confirmations", toolConfirmationsView(source));
        result.put("messages", source.stream()
                .filter(visibilityFilter::visibleForDisplay)
                .map(this::messageView)
                .toList());
        return result;
    }

    private Map<String, Object> toolConfirmationsView(List<Message> messages) {
        var states = new LinkedHashMap<String, Object>();
        for (var message : messages) {
            Map<String, Object> metadata = message.metadata() != null ? message.metadata() : Map.of();
            String confirmId = stringValue(metadata.get("confirm_id"));
            if (confirmId.isBlank()) {
                continue;
            }
            String traceType = stringValue(metadata.get("trace_type"));
            boolean confirmed = "confirmed_tool_result".equals(traceType)
                    || truthy(metadata.get("human_confirmed"))
                    || !stringValue(metadata.get("confirmed_by")).isBlank();
            if (!confirmed) {
                continue;
            }
            var item = new LinkedHashMap<String, Object>();
            item.put("confirmId", confirmId);
            item.put("status", "confirmed");
            item.put("tool", stringValue(metadata.get("tool_name")));
            item.put("confirmedBy", stringValue(metadata.get("confirmed_by")));
            item.put("createdAt", message.createdAt().toString());
            states.put(confirmId, item);
        }
        return states;
    }

    public Map<String, Object> deleteSession(String sessionId, String ownerUserId) {
        var session = store.get(sessionId);
        if (ownerUserId != null && !ownerUserId.isBlank()
                && session.ownerUserId() != null && !session.ownerUserId().isBlank()
                && !ownerUserId.equals(session.ownerUserId())) {
            throw new SecurityException("无权删除该会话");
        }

        boolean deleted = store.deleteSession(sessionId);
        return Map.of(
                "session_id", sessionId,
                "deleted", deleted,
                "success", true
        );
    }

    // ---- 阶段 2：记忆提取（后台） ----

    private void runMemoryExtraction(String sessionId, int archiveIndex, List<Message> messages) {
        try {
            log.info("Phase 2 开始: archive_{}, {} 条消息", String.format("%03d", archiveIndex), messages.size());

            // 1. 生成工作记忆
            String workingMemory = generateWorkingMemory(messages);
            log.info("Working Memory 生成完成: {} 字符", workingMemory.length());

            // 2. 写入工作记忆作为上下文条目
            String wmId = sessionId + "_wm_" + String.format("%03d", archiveIndex);
            contentService.write(new WriteRequest(wmId, workingMemory, ContextType.MEMORY, sessionId));

            // 3. 过滤长期记忆提取输入，避免工具结果、隐藏上下文和追踪消息污染记忆。
            var filtered = memoryInputFilter.filter(messages);
            log.info("记忆提取输入过滤: 原始={}，保留={}，跳过={}，截断={}",
                    filtered.originalCount(), filtered.messages().size(),
                    filtered.skippedCount(), filtered.truncatedCount());

            // 4. 提取记忆
            var candidates = extractor.extract(filtered.messages());
            log.info("提取 {} 条候选记忆", candidates.size());

            // 5. 去重 + 写入
            int created = 0;
            int skipped = 0;
            for (var c : candidates) {
                var decision = deduplicator.deduplicate(c);
                switch (decision.decision()) {
                    case "skip" -> skipped++;
                    case "create" -> {
                        String fullContent = "## " + c.category().value() + "\n\n"
                                + c.abstractText() + "\n\n" + c.overview() + "\n\n" + c.content();
                        String memId = sessionId + "_mem_" + String.format("%03d", archiveIndex) + "_" + (created + 1);
                        contentService.write(new WriteRequest(memId, fullContent, ContextType.MEMORY, null));
                        created++;
                    }
                    case "merge" -> {
                        // 简化：merge 也创建新条目（不实现合并编辑逻辑）
                        String fullContent = "## " + c.category().value() + " (merged)\n\n"
                                + c.abstractText() + "\n\n" + c.overview() + "\n\n" + c.content();
                        String memId = sessionId + "_mem_" + String.format("%03d", archiveIndex) + "_m" + (created + 1);
                        contentService.write(new WriteRequest(memId, fullContent, ContextType.MEMORY, null));
                        created++;
                    }
                }
            }

            log.info("Phase 2 完成: archive_{}, 记忆 创建={}, 跳过={}",
                    String.format("%03d", archiveIndex), created, skipped);
        } catch (Exception e) {
            log.error("Phase 2 失败: archive_{}", String.format("%03d", archiveIndex), e);
        }
    }

    // ---- 工作记忆生成 ----

    private String generateWorkingMemory(List<Message> messages) {
        var sb = new StringBuilder();
        for (var msg : messages) {
            sb.append("[").append(msg.role()).append("]: ");
            for (var part : msg.parts()) {
                if ("text".equals(part.type()) && part.text() != null) {
                    sb.append(part.text());
                }
            }
            sb.append("\n");
        }

        String prompt = prompts.render("session/working_memory",
                Map.of("messages", sb.toString()));
        return llm.chat(prompt, "", 0.0);
    }

    private Map<String, Object> messageView(Message message) {
        var item = new LinkedHashMap<String, Object>();
        item.put("id", message.id());
        item.put("role", message.role());
        item.put("content", message.parts().stream()
                .filter(p -> "text".equals(p.type()))
                .map(Message.Part::text)
                .reduce("", (a, b) -> a + b));
        item.put("metadata", message.metadata() != null ? message.metadata() : Map.of());
        item.put("created_at", message.createdAt().toString());
        return item;
    }

    private String deriveTitle(String text) {
        if (text == null || text.isBlank()) {
            return "新的对话";
        }
        String normalized = text.strip().replaceAll("\\s+", " ");
        return normalized.length() <= 30 ? normalized : normalized.substring(0, 30) + "...";
    }

    private boolean truthy(Object value) {
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    // ---- 上下文组装 ----

    /**
     * 获取会话上下文。
     * 返回工作记忆 + 最近 N 条消息。
     * memory-service 是会话的唯一拥有者，Jarvis 不自己管理上下文。
     */
    public Map<String, Object> getSessionContext(String sessionId, int maxMessages) {
        var session = store.get(sessionId);

        // 取最新的归档概览。
        String workingMemory = "";
        for (int i = session.compressionCount(); i >= 1; i--) {
            var entry = contentService.getById(sessionId + "_wm_" + String.format("%03d", i));
            if (entry != null) {
                workingMemory = entry.content();
                break;
            }
        }

        // 取最近 N 条消息
        List<Message> recentMsgs;
        if (maxMessages > 0) {
            recentMsgs = store.getRecentMessages(sessionId, maxMessages);
        } else {
            recentMsgs = store.getMessages(sessionId);
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("working_memory", workingMemory != null ? workingMemory : "");
        result.put("messages", recentMsgs.stream()
                .filter(visibilityFilter::visibleForRuntimeContext)
                .map(m -> Map.of("role", m.role(),
                        "content", visibilityFilter.textOf(m)))
                .toList());
        result.put("message_count", recentMsgs.size());
        result.put("compression_count", session.compressionCount());

        return result;
    }
}
