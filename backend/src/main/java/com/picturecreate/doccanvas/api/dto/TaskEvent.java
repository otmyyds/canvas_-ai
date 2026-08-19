package com.picturecreate.doccanvas.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.picturecreate.doccanvas.service.ai.BoardAction;

public record TaskEvent(
        String type,
        UUID taskId,
        UUID boardId,
        UUID stepId,
        String title,
        String status,
        int progress,
        String message,
        List<BoardAction> actions,
        String taskType,
        String error,
        Instant emittedAt) {

    public static TaskEvent created(UUID taskId, UUID boardId, String title) {
        return new TaskEvent("task.created", taskId, boardId, null, title, "queued", 0, null, null, null, null,
                Instant.now());
    }

    public static TaskEvent step(UUID taskId, UUID boardId, UUID stepId, String title, String status, int progress,
            String message) {
        return new TaskEvent("task.step", taskId, boardId, stepId, title, status, progress, message, null, null, null,
                Instant.now());
    }

    public static TaskEvent progress(UUID taskId, UUID boardId, int progress, String message) {
        return new TaskEvent("task.progress", taskId, boardId, null, null, null, progress, message, null, null, null,
                Instant.now());
    }

    public static TaskEvent actions(UUID taskId, UUID boardId, List<BoardAction> actions, String taskType) {
        return new TaskEvent("task.actions", taskId, boardId, null, null, null, 100, null, actions, taskType, null,
                Instant.now());
    }

    public static TaskEvent done(UUID taskId, UUID boardId, String taskType) {
        return new TaskEvent("task.done", taskId, boardId, null, null, "succeeded", 100, "任务已完成", null, taskType,
                null, Instant.now());
    }

    public static TaskEvent aborted(UUID taskId, UUID boardId) {
        return new TaskEvent("task.aborted", taskId, boardId, null, null, "aborted", 0, "任务已中断", null, null, null,
                Instant.now());
    }

    public static TaskEvent failed(UUID taskId, UUID boardId, String error) {
        return new TaskEvent("task.failed", taskId, boardId, null, null, "failed", 0, null, null, null, error,
                Instant.now());
    }
}
