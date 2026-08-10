package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.picturecreate.doccanvas.api.dto.AiTaskDtos.AiTaskResponse;
import com.picturecreate.doccanvas.api.dto.AiTaskDtos.CreateAiTaskRequest;
import com.picturecreate.doccanvas.service.AiTaskService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class AiTaskController {

    private final AiTaskService aiTaskService;
    private final CurrentUserService currentUserService;

    public AiTaskController(AiTaskService aiTaskService, CurrentUserService currentUserService) {
        this.aiTaskService = aiTaskService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/boards/{boardId}/ai-tasks")
    public AiTaskResponse create(@PathVariable UUID boardId, @Valid @RequestBody CreateAiTaskRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return aiTaskService.create(boardId, user, body.type(), body.prompt());
    }

    @GetMapping("/boards/{boardId}/ai-tasks")
    public List<AiTaskResponse> list(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return aiTaskService.list(boardId, user);
    }

    @GetMapping("/ai-tasks/{taskId}")
    public AiTaskResponse get(@PathVariable UUID taskId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return aiTaskService.get(taskId, user);
    }

    @PostMapping("/ai-tasks/{taskId}/abort")
    public AiTaskResponse abort(@PathVariable UUID taskId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return aiTaskService.abort(taskId, user);
    }
}
