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

    /** 推送失败只记日志：外部通道异常不能反向驱动任务状态 */
    public void publishQuietly(UUID boardId, UUID taskId, TaskEvent event) {
        try {
            publish(boardId, taskId, event);
        } catch (RuntimeException exception) {
            log.warn("推送任务事件失败：boardId={} taskId={} type={} error={}", boardId, taskId, event.type(),
                    exception.getMessage());
        }
    }
}
