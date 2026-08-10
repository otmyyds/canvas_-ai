package com.picturecreate.doccanvas.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "ai_task_steps")
public class AiTaskStep extends BaseEntity {

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiTaskStepStatus status = AiTaskStepStatus.PENDING;

    @Column(nullable = false)
    private int progress;

    @Column(columnDefinition = "text")
    private String message;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected AiTaskStep() {
    }

    public AiTaskStep(UUID taskId, String title) {
        this.taskId = taskId;
        this.title = title;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public String getTitle() {
        return title;
    }

    public AiTaskStepStatus getStatus() {
        return status;
    }

    public int getProgress() {
        return progress;
    }

    public String getMessage() {
        return message;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void start(String message) {
        status = AiTaskStepStatus.RUNNING;
        this.message = message;
        startedAt = Instant.now();
    }

    public void update(int progress, String message) {
        this.progress = Math.max(0, Math.min(progress, 100));
        this.message = message;
    }

    public void done(String message) {
        status = AiTaskStepStatus.DONE;
        progress = 100;
        this.message = message;
        finishedAt = Instant.now();
    }

    public void fail(String message) {
        status = AiTaskStepStatus.FAILED;
        this.message = message;
        finishedAt = Instant.now();
    }

    public void abort(String message) {
        status = AiTaskStepStatus.ABORTED;
        this.message = message;
        finishedAt = Instant.now();
    }
}
