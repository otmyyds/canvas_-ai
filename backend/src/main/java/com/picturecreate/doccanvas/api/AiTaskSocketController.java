package com.picturecreate.doccanvas.api;

import java.util.UUID;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import com.picturecreate.doccanvas.service.AiTaskService;
import com.picturecreate.doccanvas.service.CurrentUserService;

@Controller
public class AiTaskSocketController {

    private final AiTaskService aiTaskService;
    private final CurrentUserService currentUserService;

    public AiTaskSocketController(AiTaskService aiTaskService, CurrentUserService currentUserService) {
        this.aiTaskService = aiTaskService;
        this.currentUserService = currentUserService;
    }

    @MessageMapping("/tasks/abort")
    public void abort(@Payload AbortMessage message) {
        aiTaskService.abort(message.taskId(), currentUserService.demoUser());
    }

    public record AbortMessage(UUID taskId) {
    }
}
