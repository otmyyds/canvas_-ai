package com.picturecreate.doccanvas.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * AI 任务步骤实体，对应 ai_task_steps 表。
 * 一个任务含多个步骤（加载技能/收集上下文/规划操作/应用操作），
 * 每个步骤有独立的状态/进度/消息，前端横向流水线展示。
 */
@Entity
@Table(name = "ai_task_steps")
public class AiTaskStep extends BaseEntity {

    /** 所属任务 id */
    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    /** 步骤标题（如"加载技能"/"规划操作"） */
    @Column(nullable = false)
    private String title;

    /** 步骤状态：PENDING/RUNNING/DONE/FAILED/ABORTED */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiTaskStepStatus status = AiTaskStepStatus.PENDING;

    /** 步骤内进度 0-100 */
    @Column(nullable = false)
    private int progress;

    /** 步骤消息（如"已加载技能[xxx]，工具范围：..."） */
    @Column(columnDefinition = "text")
    private String message;

    /** 步骤开始时间 */
    @Column(name = "started_at")
    private Instant startedAt;

    /** 步骤结束时间 */
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
