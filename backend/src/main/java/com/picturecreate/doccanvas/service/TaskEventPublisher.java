package com.picturecreate.doccanvas.service;

import java.util.UUID;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.TaskEvent;

@Component
public class TaskEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TaskEventPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;

    public TaskEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publish(UUID boardId, UUID taskId, TaskEvent event) {
        log.debug("推送任务事件：boardId={} taskId={}", boardId, taskId);
        messagingTemplate.convertAndSend("/topic/boards/%s/tasks/%s".formatted(boardId, taskId), event);
        messagingTemplate.convertAndSend("/topic/boards/%s/tasks".formatted(boardId), event);
    }
}
