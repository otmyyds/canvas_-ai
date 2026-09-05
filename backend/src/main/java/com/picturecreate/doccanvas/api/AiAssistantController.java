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
import com.picturecreate.doccanvas.domain.AiTaskType;
import com.picturecreate.doccanvas.service.AiTaskService;
import com.picturecreate.doccanvas.service.ChatService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.service.ai.IntentClassifier;
import com.picturecreate.doccanvas.service.ai.IntentClassifier.IntentDecision;
import com.picturecreate.doccanvas.service.ai.ScopeGuard;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * AI 助手统一入口控制器。
 *
 * 前端不再用关键词表判断「该聊天还是该执行任务」，统一提交到本接口，
 * 由后端意图分类器（IntentClassifier）决定路由：
 * - CHAT → 纯文本回复（ChatService，带 RAG 检索）
 * - 生成类任务 → 创建 AI 任务（AiTaskService，异步执行落图形节点）
 *
 * 区别于 ChatController / AiTaskController：那两个是底层原子能力（显式指定意图），
 * 本接口是面向对话面板的唯一智能入口（由 AI 判断意图）。
 *
 * 接口：POST /api/boards/{boardId}/assist  body: { prompt }  →  { kind, reply?, task? }
 */
@RestController
public class AiAssistantController {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantController.class);

    private final IntentClassifier intentClassifier;
    private final ChatService chatService;
    private final AiTaskService aiTaskService;
    private final CurrentUserService currentUserService;
    private final ScopeGuard scopeGuard;

    public AiAssistantController(IntentClassifier intentClassifier, ChatService chatService,
            AiTaskService aiTaskService, CurrentUserService currentUserService, ScopeGuard scopeGuard) {
        this.intentClassifier = intentClassifier;
        this.chatService = chatService;
        this.aiTaskService = aiTaskService;
        this.currentUserService = currentUserService;
        this.scopeGuard = scopeGuard;
    }

    /**
     * 统一 AI 助手入口：分类意图后分流到聊天或任务。
     */
    @PostMapping("/api/boards/{boardId}/assist")
    public AssistResponse assist(@PathVariable UUID boardId, @Valid @RequestBody AssistRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        List<String> selectedShapeIds = body.selectedShapeIds() == null ? List.of() : body.selectedShapeIds();
        log.info("AI 助手请求：boardId={} prompt={} 选中图形={}", boardId, body.prompt(),
                selectedShapeIds.size());
        // 能力范围硬兜底：明显越界直接拒绝，不调模型
        String refusal = scopeGuard.refuseReason(body.prompt());
        if (refusal != null) {
            return AssistResponse.chat(refusal);
        }
        boolean hasSelection = !selectedShapeIds.isEmpty();
        IntentDecision decision = intentClassifier.classify(body.prompt(), hasSelection);
        if (decision.isChat()) {
            log.info("意图分类：CHAT");
            String reply = chatService.chat(boardId, user, body.prompt());
            return AssistResponse.chat(reply);
        }
        List<AiTaskType> plan = decision.plan();
        log.info("意图分类：任务 plan={}", plan);
        // 多步骤计划：创建首任务，剩余步骤存入上下文由 AiTaskService 链式续跑
        AiTaskType first = plan.get(0);
        List<AiTaskType> remaining = plan.subList(1, plan.size());
        AiTaskResponse task = aiTaskService.create(boardId, user, first, body.prompt(), selectedShapeIds, remaining);
        return AssistResponse.task(task);
    }

    /** 请求体：用户输入文本 + 可选的选中图形节点 id（用于编辑选中元素的任务） */
    public record AssistRequest(
            @NotBlank(message = "输入不能为空") String prompt,
            List<String> selectedShapeIds) {
    }

    /** 响应体：kind=chat 时 reply 有值，kind=task 时 task 有值 */
    public record AssistResponse(String kind, String reply, AiTaskResponse task) {

        public static AssistResponse chat(String reply) {
            return new AssistResponse("chat", reply, null);
        }

        public static AssistResponse task(AiTaskResponse task) {
            return new AssistResponse("task", null, task);
        }
    }
}
