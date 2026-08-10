package com.picturecreate.doccanvas.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "ai_tasks")
public class AiTask extends BaseEntity {

    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiTaskType type;

    @Column(nullable = false, columnDefinition = "text")
    private String prompt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiTaskStatus status = AiTaskStatus.CREATED;

    @Column(nullable = false)
    private int progress;

    @Column(name = "current_step")
    private String currentStep;

    @Column(name = "abort_requested", nullable = false)
    private boolean abortRequested;

    @Column(name = "result_json", columnDefinition = "text")
    private String resultJson;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "lock_expires_at")
    private Instant lockExpiresAt;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "started_at")
    private Instant startedAt;

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

    public void markRunning(String workerId) {
        status = AiTaskStatus.RUNNING;
        lockedBy = workerId;
        startedAt = startedAt == null ? Instant.now() : startedAt;
        heartbeat();
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
