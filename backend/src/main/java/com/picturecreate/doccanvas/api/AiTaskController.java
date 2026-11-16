package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.AiTaskDtos.AiTaskResponse;
import com.picturecreate.doccanvas.common.Result;
import com.picturecreate.doccanvas.service.AiTaskService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * AI 任务（AiTask）查询/中断接口控制器。
 *
 * 创建任务已收敛到 AiAssistantController（统一入口，内部直接调 AiTaskService.create），
 * 本控制器只保留查询与中断能力（前端任务列表/进度/中断仍依赖）：
 *
 * 接口一览（前缀 /api）：
 * - GET    /boards/{boardId}/ai-tasks   → 画布下所有任务（含步骤）
 * - POST   /ai-tasks/{taskId}/abort     → 中断任务
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

    /** 画布下所有任务（按创建时间倒序，含步骤列表） */
    @GetMapping("/boards/{boardId}/ai-tasks")
    public Result<List<AiTaskResponse>> list(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("查询画布任务列表：boardId={}", boardId);
        return Result.success(aiTaskService.list(boardId, user));
    }

    /** 中断任务（DB 标记 + Redis cancel key） */
    @PostMapping("/ai-tasks/{taskId}/abort")
    public Result<AiTaskResponse> abort(@PathVariable UUID taskId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("中断任务：taskId={}", taskId);
        return Result.success(aiTaskService.abort(taskId, user));
    }
}
