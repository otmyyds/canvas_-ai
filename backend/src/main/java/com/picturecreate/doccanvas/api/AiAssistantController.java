package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.UUID;


import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.AiTaskDtos.AiTaskResponse;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.service.ai.ScopeGuard;
import com.picturecreate.doccanvas.service.ai.agent.AgentAssistService;
import com.picturecreate.doccanvas.service.ai.agent.AgentAssistService.AssistOutcome;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * AI 助手统一入口控制器。
 *
 * 前端不再判断「该聊天还是该执行任务」，统一提交到本接口，
 * 由预检 Supervisor 决定路由：
 * - 直接回答 → reply 文本
 * - 需要改画布 → 创建后台任务，由更完整的 Supervisor 执行
 *
 * 能力范围硬兜底（ScopeGuard）仍然保留：明显越界的请求在调用模型前直接拒绝。
 *
 * 接口：POST /api/boards/{boardId}/assist
 * body: { prompt, selectedShapeIds?, sessionId? }
 * 响应：{ kind: "reply", reply, sessionId } 或 { kind: "task", task, sessionId }
 */
@RestController
public class AiAssistantController {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantController.class);

    private final AgentAssistService agentAssistService;
    private final CurrentUserService currentUserService;
    private final ScopeGuard scopeGuard;

    public AiAssistantController(AgentAssistService agentAssistService, CurrentUserService currentUserService,
            ScopeGuard scopeGuard) {
        this.agentAssistService = agentAssistService;
        this.currentUserService = currentUserService;
        this.scopeGuard = scopeGuard;
    }

    /** 统一 AI 助手入口：预检后直接回复或创建任务 */
    @PostMapping("/api/boards/{boardId}/assist")
    public AssistResponse assist(@PathVariable UUID boardId, @Valid @RequestBody AssistRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        List<String> selectedShapeIds = body.selectedShapeIds() == null ? List.of() : body.selectedShapeIds();
        log.info("AI 助手请求：boardId={} prompt={} 选中图形={}", boardId, body.prompt(),
                selectedShapeIds.size());
        // 能力范围硬兜底：明显越界直接拒绝，不调模型；此时不创建会话
        String refusal = scopeGuard.refuseReason(body.prompt());
        if (refusal != null) {
            return AssistResponse.reply(refusal, null);
        }
        AssistOutcome outcome = agentAssistService.assist(boardId, user, body.prompt(), selectedShapeIds,
                body.sessionId());
        if (outcome.task() != null) {
            return AssistResponse.task(outcome.task(), outcome.sessionId());
        }
        return AssistResponse.reply(outcome.reply(), outcome.sessionId());
    }

    /**
     * 请求体：用户输入文本 + 可选的选中图形节点 id + 会话 id。
     * sessionId 为空时后端新建会话，响应会带回新的 id。
     */
    public record AssistRequest(
            @NotBlank(message = "输入不能为空") String prompt,
            List<String> selectedShapeIds,
            UUID sessionId) {
    }

    /** 响应体：kind=reply 时 reply 有值，kind=task 时 task 有值；sessionId 用于后续多轮对话 */
    public record AssistResponse(String kind, String reply, AiTaskResponse task, UUID sessionId) {

        public static AssistResponse reply(String reply, UUID sessionId) {
            return new AssistResponse("reply", reply, null, sessionId);
        }

        public static AssistResponse task(AiTaskResponse task, UUID sessionId) {
            return new AssistResponse("task", null, task, sessionId);
        }
    }
}
