package com.picturecreate.doccanvas.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * AI 任务实体，对应 ai_tasks 表。
 * 记录一次 AI 生成请求的完整状态机与执行信息。
 * 状态流转：CREATED → QUEUED → RUNNING → SUCCEEDED / FAILED / ABORTED
 */
@Entity
@Table(name = "ai_tasks")
public class AiTask extends BaseEntity {

    /** 所属画布 id */
    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    /** 发起任务的用户 id（owner 隔离） */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** 任务类型：思维导图/流程图/总结等（决定生成方式） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiTaskType type;

    /** 用户输入（原始 prompt） */
    @Column(nullable = false, columnDefinition = "text")
    private String prompt;

    /** 任务上下文 JSON（如选中的图形节点 id 列表），供异步执行时还原请求上下文 */
    @Column(name = "context_json", columnDefinition = "text")
    private String contextJson;

    /** 任务状态（状态机核心字段） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiTaskStatus status = AiTaskStatus.CREATED;

    /** 进度 0-100（WebSocket 推送用） */
    @Column(nullable = false)
    private int progress;

    /** 当前执行步骤名（前端展示"正在做什么"） */
    @Column(name = "current_step")
    private String currentStep;

    /** 用户是否请求中断（执行中的安全检查点据此停止） */
    @Column(name = "abort_requested", nullable = false)
    private boolean abortRequested;

    /** 成功后的结果 JSON（动作元信息等） */
    @Column(name = "result_json", columnDefinition = "text")
    private String resultJson;

    /** 失败原因（任务 FAILED 时记录） */
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    /** 已重试次数（配合 maxAttempts） */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /** 最大重试次数（默认 3） */
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    /** 正在执行任务的 worker 标识（分布式锁） */
    @Column(name = "locked_by")
    private String lockedBy;

    /** 锁过期时间（worker 心跳超时后锁释放） */
    @Column(name = "lock_expires_at")
    private Instant lockExpiresAt;

    /** 幂等键（防止重复提交） */
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    /** 最后心跳时间（检测 worker 是否存活） */
    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    /** 任务开始时间 */
    @Column(name = "started_at")
    private Instant startedAt;

    /** 任务结束时间（成功/失败/中断时设置） */
    @Column(name = "finished_at")
    private Instant finishedAt;

    protected AiTask() {
    }

    public AiTask(UUID boardId, UUID ownerId, AiTaskType type, String prompt) {
        this.boardId = boardId;
        this.ownerId = ownerId;
        this.type = type;
        this.prompt = prompt;
        this.status = AiTaskStatus.QUEUED;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public AiTaskType getType() {
        return type;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getContextJson() {
        return contextJson;
    }

    public void setContextJson(String contextJson) {
        this.contextJson = contextJson;
    }

    public AiTaskStatus getStatus() {
        return status;
    }

    public int getProgress() {
        return progress;
    }

    public String getCurrentStep() {
        return currentStep;
    }

    public boolean isAbortRequested() {
        return abortRequested;
    }

    public String getResultJson() {
        return resultJson;
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

    public void updateProgress(int progress, String currentStep) {
        this.progress = Math.max(0, Math.min(progress, 100));
        this.currentStep = currentStep;
        heartbeat();
    }

    public void requestAbort() {
        abortRequested = true;
        if (status == AiTaskStatus.RUNNING || status == AiTaskStatus.QUEUED) {
            status = AiTaskStatus.ABORTING;
        }
    }

    public void markSucceeded(String resultJson) {
        status = AiTaskStatus.SUCCEEDED;
        progress = 100;
        this.resultJson = resultJson;
        finishedAt = Instant.now();
        heartbeat();
    }

    public void markFailed(String errorMessage) {
        status = AiTaskStatus.FAILED;
        this.errorMessage = errorMessage;
        finishedAt = Instant.now();
        heartbeat();
    }

    public void markAborted() {
        status = AiTaskStatus.ABORTED;
        finishedAt = Instant.now();
        heartbeat();
    }

    public void heartbeat() {
        lastHeartbeatAt = Instant.now();
        lockExpiresAt = lastHeartbeatAt.plusSeconds(90);
    }
}
