package com.picturecreate.doccanvas.service.ai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.domain.AgentMessage;
import com.picturecreate.doccanvas.domain.AgentMessageRole;
import com.picturecreate.doccanvas.domain.AgentSession;
import com.picturecreate.doccanvas.repository.AgentMessageRepository;
import com.picturecreate.doccanvas.repository.AgentSessionRepository;
import com.picturecreate.doccanvas.service.BoardService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.NotFoundException;

/**
 * Agent 会话记忆：按 owner + board 隔离的对话会话、消息与滚动摘要。
 *
 * 上下文只装配两层：最近 N 条用户/助手消息，以及更早消息压缩成的摘要。
 * 工具轨迹在 agent_steps，不进入会话上下文；完整原始消息保留在 agent_messages 供回看。
 */
@Service
public class AgentSessionService {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionService.class);

    /** 会话标题上限，过长时截断 */
    private static final int TITLE_MAX_LENGTH = 40;

    /** 单条消息进入上下文时的截断长度；需要容纳任务摘要里的分支 id */
    private static final int MESSAGE_CONTEXT_MAX_LENGTH = 1200;

    /** 滚动摘要总长度上限，避免摘要本身膨胀 */
    private static final int SUMMARY_MAX_LENGTH = 4000;

    private final AgentSessionRepository sessionRepository;
    private final AgentMessageRepository messageRepository;
    private final BoardService boardService;
    private final AppProperties properties;

    public AgentSessionService(AgentSessionRepository sessionRepository, AgentMessageRepository messageRepository,
            BoardService boardService, AppProperties properties) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.boardService = boardService;
        this.properties = properties;
    }

    /**
     * 打开会话：sessionId 为空时新建，否则读取并校验归属与画布。
     * 传入 sessionId 时必须同时属于当前用户和当前画布，否则按不存在处理。
     */
    @Transactional
    public AgentSession open(UUID boardId, CurrentUser user, UUID sessionId, String firstPrompt) {
        boardService.getOwned(boardId, user);
        if (sessionId == null) {
            AgentSession created = sessionRepository.save(
                    new AgentSession(user.id(), boardId, titleFrom(firstPrompt)));
            log.info("创建 Agent 会话：sessionId={} boardId={} 标题={}", created.getId(), boardId,
                    created.getTitle());
            return created;
        }
        return require(boardId, sessionId, user);
    }

    /** 加载会话上下文：摘要 + 最近窗口内消息；会话不存在时返回空上下文 */
    @Transactional(readOnly = true)
    public SessionMemory loadMemory(UUID sessionId) {
        if (sessionId == null) {
            return SessionMemory.empty();
        }
        AgentSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return SessionMemory.empty();
        }
        int window = Math.max(1, properties.ai().agent().sessionWindowMessages());
        List<AgentMessage> all = messageRepository.findBySessionIdAndOwnerIdOrderByCreatedAtAsc(
                session.getId(), session.getOwnerId());
        int from = Math.max(0, all.size() - window);
        return new SessionMemory(session.getSummary(), renderHistory(all.subList(from, all.size())));
    }

    /** 记录用户消息；超过阈值时把窗口外的消息压缩进滚动摘要 */
    @Transactional
    public void appendUser(UUID sessionId, String content, UUID runId) {
        append(sessionId, AgentMessageRole.USER, content, null, runId);
    }

    /** 记录助手回复（直接回复或“已创建计划”）；超过阈值时同步更新摘要 */
    @Transactional
    public void appendAssistant(UUID sessionId, String content, UUID taskId, UUID runId) {
        append(sessionId, AgentMessageRole.ASSISTANT, content, taskId, runId);
    }

    /** 后台计划结束时追加任务摘要，让下一轮对话能看到上一轮生成了什么 */
    @Transactional
    public void appendTaskSummary(UUID sessionId, String content, UUID taskId) {
        append(sessionId, AgentMessageRole.ASSISTANT, content, taskId, null);
    }

    /** 当前用户在某画布下的会话列表，最近活跃在前 */
    @Transactional(readOnly = true)
    public List<AgentSession> list(UUID boardId, CurrentUser user) {
        boardService.getOwned(boardId, user);
        return sessionRepository.findByOwnerIdAndBoardIdOrderByLastActiveAtDesc(user.id(), boardId);
    }

    /** 会话消息（按时间升序）；会话必须属于当前用户和当前画布 */
    @Transactional(readOnly = true)
    public List<AgentMessage> messages(UUID boardId, UUID sessionId, CurrentUser user) {
        AgentSession session = require(boardId, sessionId, user);
        return messageRepository.findBySessionIdAndOwnerIdOrderByCreatedAtAsc(session.getId(),
                session.getOwnerId());
    }

    /** 删除会话；消息由数据库外键级联删除，运行记录只解除关联 */
    @Transactional
    public void delete(UUID boardId, UUID sessionId, CurrentUser user) {
        AgentSession session = require(boardId, sessionId, user);
        sessionRepository.delete(session);
        log.info("删除 Agent 会话：sessionId={} boardId={}", sessionId, boardId);
    }

    /** 写入消息并按需更新摘要与活跃时间 */
    private void append(UUID sessionId, AgentMessageRole role, String content, UUID taskId, UUID runId) {
        if (sessionId == null || content == null || content.isBlank()) {
            return;
        }
        AgentSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.warn("会话不存在，跳过消息写入：sessionId={} role={}", sessionId, role);
            return;
        }
        messageRepository.save(new AgentMessage(session.getId(), session.getOwnerId(), session.getBoardId(),
                role, content, null, null, taskId, runId));
        updateRollingSummary(session);
        sessionRepository.save(session);
    }

    /** 消息数超过阈值时，把最近窗口之外的消息重新压缩成摘要 */
    private void updateRollingSummary(AgentSession session) {
        int threshold = Math.max(1, properties.ai().agent().sessionSummaryThreshold());
        long count = messageRepository.countBySessionId(session.getId());
        if (count <= threshold) {
            session.touch();
            return;
        }
        int window = Math.max(1, properties.ai().agent().sessionWindowMessages());
        List<AgentMessage> all = messageRepository.findBySessionIdAndOwnerIdOrderByCreatedAtAsc(
                session.getId(), session.getOwnerId());
        int olderEnd = all.size() - window;
        if (olderEnd <= 0) {
            session.touch();
            return;
        }
        session.updateSummary(renderSummary(all.subList(0, olderEnd)));
    }

    /** 把窗口外的消息渲染为摘要；只保留用户与助手可见内容，并对总长度设上限 */
    private String renderSummary(List<AgentMessage> messages) {
        StringBuilder summary = new StringBuilder();
        for (AgentMessage message : messages) {
            if (message.getRole() != AgentMessageRole.USER && message.getRole() != AgentMessageRole.ASSISTANT) {
                continue;
            }
            if (message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            summary.append(message.getRole() == AgentMessageRole.USER ? "用户：" : "助手：")
                    .append(abbreviate(message.getContent(), MESSAGE_CONTEXT_MAX_LENGTH * 2))
                    .append('\n');
        }
        if (summary.length() <= SUMMARY_MAX_LENGTH) {
            return summary.toString().trim();
        }
        // 超长时保留最近部分；更早的目标仍可从原始消息回看
        return "（更早记录已省略）\n"
                + summary.substring(summary.length() - SUMMARY_MAX_LENGTH);
    }

    /** 最近窗口消息渲染为“用户：/助手：”文本 */
    private String renderHistory(List<AgentMessage> messages) {
        StringBuilder history = new StringBuilder();
        for (AgentMessage message : messages) {
            if (message.getRole() != AgentMessageRole.USER && message.getRole() != AgentMessageRole.ASSISTANT) {
                continue;
            }
            if (message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            history.append(message.getRole() == AgentMessageRole.USER ? "用户：" : "助手：")
                    .append(abbreviate(message.getContent(), MESSAGE_CONTEXT_MAX_LENGTH))
                    .append('\n');
        }
        return history.toString().trim();
    }

    /** 按 owner + board 读取会话，不匹配时统一按不存在处理 */
    private AgentSession require(UUID boardId, UUID sessionId, CurrentUser user) {
        AgentSession session = sessionRepository.findByIdAndOwnerId(sessionId, user.id())
                .orElseThrow(() -> new NotFoundException("Agent 会话不存在"));
        if (!session.getBoardId().equals(boardId)) {
            throw new NotFoundException("Agent 会话不存在");
        }
        return session;
    }

    /** 会话标题取首条用户输入，压缩空白并截断 */
    private String titleFrom(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "新对话";
        }
        String title = prompt.replaceAll("\\s+", " ").trim();
        return title.length() <= TITLE_MAX_LENGTH ? title : title.substring(0, TITLE_MAX_LENGTH);
    }

    /** 截断过长文本，保留开头部分 */
    private String abbreviate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "……";
    }

    /**
     * 会话上下文：滚动摘要与最近消息文本。
     * render() 为空串表示没有历史，提示词统一显示“无”。
     */
    public record SessionMemory(String summary, String history) {

        public static SessionMemory empty() {
            return new SessionMemory(null, null);
        }

        public String render() {
            List<String> parts = new ArrayList<>();
            if (summary != null && !summary.isBlank()) {
                parts.add("会话摘要：\n" + summary);
            }
            if (history != null && !history.isBlank()) {
                parts.add("最近对话：\n" + history);
            }
            return String.join("\n\n", parts);
        }
    }
}
