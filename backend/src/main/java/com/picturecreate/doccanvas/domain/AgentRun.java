package com.picturecreate.doccanvas.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Agent 运行记录，对应 agent_runs 表。
 * 一次任务可能产生多条运行记录（Supervisor、分支 Worker、Critic 各一条），
 * 只追加和收尾，不驱动 ai_tasks 的状态机。
 */
@Entity
@Table(name = "agent_runs")
public class AgentRun {

    /** 主键 */
    @Id
    @GeneratedValue
    private UUID id;

    /** 关联的 AI 任务；预检等同步运行可以为空 */
    @Column(name = "task_id")
    private UUID taskId;

    /** 关联的会话；无会话的定时任务可以为空 */
    @Column(name = "session_id")
    private UUID sessionId;

    /** 父运行 id，委派树用；Supervisor 为空 */
    @Column(name = "parent_run_id")
    private UUID parentRunId;

    /** 运行归属用户，所有查询必须带此条件 */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** 运行所属画布 */
    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    /** Agent 角色 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentRole role;

    /** 分支标识（仅分支 Worker 使用） */
    @Column(name = "branch_key", length = 128)
    private String branchKey;

    /** 运行状态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentRunStatus status = AgentRunStatus.RUNNING;

    /** 实际使用的模型名 */
    @Column(length = 255)
    private String model;

    /** 本运行内的模型调用次数 */
    @Column(name = "model_calls", nullable = false)
    private int modelCalls;

    /** 本运行内的工具调用次数 */
    @Column(name = "tool_calls", nullable = false)
    private int toolCalls;

    /** 输入 token */
    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    /** 输出 token */
    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    /** 总 token（输入 + 输出） */
    @Column(name = "total_tokens", nullable = false)
    private int totalTokens;

    /** 运行结果 JSON（分支结果或最终摘要） */
    @Column(name = "result_json", columnDefinition = "text")
    private String resultJson;

    /** 失败原因 */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** 开始时间 */
    @Column(name = "started_at")
    private Instant startedAt;

    /** 结束时间 */
    @Column(name = "finished_at")
    private Instant finishedAt;

    /** 创建时间（agent_runs 没有 updated_at，不继承 BaseEntity） */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AgentRun() {
    }

    public AgentRun(UUID taskId, UUID sessionId, UUID parentRunId, UUID ownerId, UUID boardId,
            AgentRole role, String branchKey, String model) {
        this.taskId = taskId;
        this.sessionId = sessionId;
        this.parentRunId = parentRunId;
        this.ownerId = ownerId;
        this.boardId = boardId;
        this.role = role;
        this.branchKey = branchKey;
        this.model = model;
        this.startedAt = Instant.now();
    }

    /** 结束本次运行 */
    public void finish(AgentRunStatus status, String resultJson, String errorMessage) {
        this.status = status;
        this.resultJson = resultJson;
        this.errorMessage = errorMessage;
        this.finishedAt = Instant.now();
    }

    /** 累加模型调用、工具调用和 token 用量 */
    public void addUsage(int modelCalls, int toolCalls, int promptTokens, int completionTokens) {
        this.modelCalls += modelCalls;
        this.toolCalls += toolCalls;
        this.promptTokens += promptTokens;
        this.completionTokens += completionTokens;
        this.totalTokens = this.promptTokens + this.completionTokens;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getParentRunId() {
        return parentRunId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public AgentRole getRole() {
        return role;
    }

    public String getBranchKey() {
        return branchKey;
    }

    public AgentRunStatus getStatus() {
        return status;
    }

    public String getModel() {
        return model;
    }

    public int getModelCalls() {
        return modelCalls;
    }

    public int getToolCalls() {
        return toolCalls;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
