package com.picturecreate.doccanvas.api;

import java.util.UUID;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.service.AiTaskService;
import com.picturecreate.doccanvas.service.CurrentUserService;

@Controller
public class AiTaskSocketController {

    private static final Logger log = LoggerFactory.getLogger(AiTaskSocketController.class);

    private final AiTaskService aiTaskService;
    private final CurrentUserService currentUserService;

    public AiTaskSocketController(AiTaskService aiTaskService, CurrentUserService currentUserService) {
        this.aiTaskService = aiTaskService;
        this.currentUserService = currentUserService;
    }

    @MessageMapping("/tasks/abort")
    public void abort(@Payload AbortMessage message) {
        log.info("WebSocket 中断任务：taskId={}", message.taskId());
        aiTaskService.abort(message.taskId(), currentUserService.demoUser());
    }

    public record AbortMessage(UUID taskId) {
    }
}
