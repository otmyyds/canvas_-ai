package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.AiTaskDtos.AiTaskResponse;
import com.picturecreate.doccanvas.api.dto.AiTaskDtos.CreateAiTaskRequest;
import com.picturecreate.doccanvas.service.AiTaskService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * AI 任务（AiTask）接口控制器。
 *
 * 职责：AI 任务的创建、查询、中断。
 * 任务创建后由 AiTaskService 异步执行（不阻塞 HTTP 请求），
 * 进度通过 WebSocket 推送（见 use-task-stream 前端消费）。
 *
 * 接口一览（前缀 /api）：
 * - POST   /boards/{boardId}/ai-tasks      → 创建 AI 任务（body: type/prompt）
 * - GET    /boards/{boardId}/ai-tasks      → 画布下所有任务（含步骤）
 * - GET    /ai-tasks/{taskId}              → 单个任务详情（含步骤）
 * - POST   /ai-tasks/{taskId}/abort        → 中断任务
 */
@RestController
@RequestMapping("/api")
public class AiTaskController {

    private static final Logger log = LoggerFactory.getLogger(AiTaskController.class);

    private final AiTaskService aiTaskService;
    private final CurrentUserService currentUserService;

    public AiTaskController(AiTaskService aiTaskService, CurrentUserService currentUserService) {
        this.aiTaskService = aiTaskService;
        this.currentUserService = currentUserService;
    }

    /** 创建 AI 任务：返回 QUEUED 状态的任务，异步开始执行 */
    @PostMapping("/boards/{boardId}/ai-tasks")
    public AiTaskResponse create(@PathVariable UUID boardId, @Valid @RequestBody CreateAiTaskRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("创建 AI 任务：boardId={} type={} prompt={}", boardId, body.type(), body.prompt());
        return aiTaskService.create(boardId, user, body.type(), body.prompt());
    }

    /** 画布下所有任务（按创建时间倒序，含步骤列表） */
    @GetMapping("/boards/{boardId}/ai-tasks")
    public List<AiTaskResponse> list(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("查询画布任务列表：boardId={}", boardId);
        return aiTaskService.list(boardId, user);
    }

    /** 单个任务详情（前端刷新/轮询用） */
    @GetMapping("/ai-tasks/{taskId}")
    public AiTaskResponse get(@PathVariable UUID taskId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("查询任务详情：taskId={}", taskId);
        return aiTaskService.get(taskId, user);
    }

    /** 中断任务（DB 标记 + Redis cancel key） */
    @PostMapping("/ai-tasks/{taskId}/abort")
    public AiTaskResponse abort(@PathVariable UUID taskId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("中断任务：taskId={}", taskId);
        return aiTaskService.abort(taskId, user);
    }
}
