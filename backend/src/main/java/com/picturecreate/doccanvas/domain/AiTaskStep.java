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
 * 一个计划任务含多个有序步骤，每个步骤独立记录类型、说明、执行指令、状态与结果，
 * 前端按 seq 纵向展示计划与每步效果。
 */
@Entity
@Table(name = "ai_task_steps")
public class AiTaskStep extends BaseEntity {

    /** 所属任务 id */
    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    /** 步骤顺序，从 1 开始 */
    @Column(nullable = false)
    private int seq;

    /** 步骤类型，复用任务类型表示这一步做什么 */
    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", length = 32)
    private AiTaskType stepType;

    /** 步骤标题（展示给用户） */
    @Column(nullable = false)
    private String title;

    /** 步骤说明（展示给用户：这一步做什么） */
    @Column(columnDefinition = "text")
    private String description;

    /** 本步交给生成 Agent 的独立指令 */
    @Column(columnDefinition = "text")
    private String brief;

    /** 步骤状态：PENDING/RUNNING/DONE/FAILED/ABORTED/SKIPPED */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiTaskStepStatus status = AiTaskStepStatus.PENDING;

    /** 步骤内进度 0-100 */
    @Column(nullable = false)
    private int progress;

    /** 步骤执行中的进度消息 */
    @Column(columnDefinition = "text")
    private String message;

    /** 步骤完成后的效果说明（如"新增 18 个节点、17 条连线"） */
    @Column(name = "result_message", columnDefinition = "text")
    private String resultMessage;

    /** 步骤失败原因 */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** 步骤开始时间 */
    @Column(name = "started_at")
    private Instant startedAt;

    /** 步骤结束时间 */
    @Column(name = "finished_at")
    private Instant finishedAt;

    protected AiTaskStep() {
    }

    public AiTaskStep(UUID taskId, int seq, AiTaskType stepType, String title, String description, String brief) {
        this.taskId = taskId;
        this.seq = seq;
        this.stepType = stepType;
        this.title = title;
        this.description = description;
        this.brief = brief;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public int getSeq() {
        return seq;
    }

    public AiTaskType getStepType() {
        return stepType;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getBrief() {
        return brief;
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

    public String getResultMessage() {
        return resultMessage;
    }

    public String getErrorMessage() {
        return errorMessage;
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

    public void done(String resultMessage) {
        status = AiTaskStepStatus.DONE;
        progress = 100;
        this.resultMessage = resultMessage;
        this.message = resultMessage;
        finishedAt = Instant.now();
    }

    public void fail(String errorMessage) {
        status = AiTaskStepStatus.FAILED;
        this.errorMessage = errorMessage;
        this.message = errorMessage;
        finishedAt = Instant.now();
    }

    public void abort(String message) {
        status = AiTaskStepStatus.ABORTED;
        this.message = message;
        finishedAt = Instant.now();
    }

    /** 前序步骤终止导致本步未执行 */
    public void skip() {
        status = AiTaskStepStatus.SKIPPED;
        finishedAt = Instant.now();
    }
}
