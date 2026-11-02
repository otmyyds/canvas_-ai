package com.picturecreate.doccanvas.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.picturecreate.doccanvas.domain.AgentMessage;
import com.picturecreate.doccanvas.domain.AgentMessageRole;
import com.picturecreate.doccanvas.domain.AgentSession;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.service.ai.agent.AgentSessionService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Agent 会话接口：列表、消息回看、删除。
 * 会话创建发生在 /assist 首次请求时，这里不提供单独的新建入口。
 * 所有接口都按 owner + board 隔离，跨用户或跨画布一律 404。
 */
@RestController
public class AgentSessionController {

    private final AgentSessionService sessionService;
    private final CurrentUserService currentUserService;

    public AgentSessionController(AgentSessionService sessionService, CurrentUserService currentUserService) {
        this.sessionService = sessionService;
        this.currentUserService = currentUserService;
    }

    /** 画布下的会话列表（最近活跃在前） */
    @GetMapping("/api/boards/{boardId}/agent/sessions")
    public List<AgentSessionResponse> list(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return sessionService.list(boardId, user).stream().map(AgentSessionResponse::from).toList();
    }

    /** 单个会话的消息（按时间升序） */
    @GetMapping("/api/boards/{boardId}/agent/sessions/{sessionId}/messages")
    public List<AgentMessageResponse> messages(@PathVariable UUID boardId, @PathVariable UUID sessionId,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return sessionService.messages(boardId, sessionId, user).stream().map(AgentMessageResponse::from).toList();
    }

    /** 删除会话；消息级联删除，运行记录只解除会话关联 */
    @DeleteMapping("/api/boards/{boardId}/agent/sessions/{sessionId}")
    public ResponseEntity<Void> delete(@PathVariable UUID boardId, @PathVariable UUID sessionId,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        sessionService.delete(boardId, sessionId, user);
        return ResponseEntity.noContent().build();
    }

    /** 会话摘要信息 */
    public record AgentSessionResponse(UUID id, UUID boardId, String title, String summary,
            Instant lastActiveAt, Instant createdAt) {

        public static AgentSessionResponse from(AgentSession session) {
            return new AgentSessionResponse(session.getId(), session.getBoardId(), session.getTitle(),
                    session.getSummary(), session.getLastActiveAt(), session.getCreatedAt());
        }
    }

    /** 会话消息：只暴露用户可见文本与关联任务，不包含工具完整输入输出 */
    public record AgentMessageResponse(UUID id, AgentMessageRole role, String content, UUID taskId, UUID runId,
            Instant createdAt) {

        public static AgentMessageResponse from(AgentMessage message) {
            return new AgentMessageResponse(message.getId(), message.getRole(), message.getContent(),
                    message.getTaskId(), message.getRunId(), message.getCreatedAt());
        }
    }
}
