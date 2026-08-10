package com.picturecreate.doccanvas.service;

import java.util.UUID;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.api.dto.TaskEvent;

@Component
public class TaskEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public TaskEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publish(UUID boardId, UUID taskId, TaskEvent event) {
        messagingTemplate.convertAndSend("/topic/boards/%s/tasks/%s".formatted(boardId, taskId), event);
        messagingTemplate.convertAndSend("/topic/boards/%s/tasks".formatted(boardId), event);
    }
}
