package com.picturecreate.doccanvas.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Agent 步骤记录，对应 agent_steps 表。
 * 只追加、不更新，完整保存模型调用和工具输入输出，供审计与调试。
 */
@Entity
@Table(name = "agent_steps")
public class AgentStep {

    /** 主键 */
    @Id
    @GeneratedValue
    private UUID id;

    /** 所属运行 id */
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    /** 运行内序号，从 1 开始 */
    @Column(nullable = false)
    private int seq;

    /** 步骤类型 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentStepType type;

    /** 工具名（模型调用时可为模型名） */
    @Column(name = "tool_name", length = 128)
    private String toolName;

    /** 工具输入 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_json", columnDefinition = "jsonb")
    private JsonNode inputJson;

    /** 工具输出 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_json", columnDefinition = "jsonb")
    private JsonNode outputJson;

    /** 耗时（毫秒） */
    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** 错误信息 */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** 创建时间（agent_steps 没有 updated_at，不继承 BaseEntity） */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AgentStep() {
    }

    public AgentStep(UUID runId, int seq, AgentStepType type, String toolName, JsonNode inputJson,
            JsonNode outputJson, Integer latencyMs, String errorMessage) {
        this.runId = runId;
        this.seq = seq;
        this.type = type;
        this.toolName = toolName;
        this.inputJson = inputJson;
        this.outputJson = outputJson;
        this.latencyMs = latencyMs;
        this.errorMessage = errorMessage;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public int getSeq() {
        return seq;
    }

    public AgentStepType getType() {
        return type;
    }

    public String getToolName() {
        return toolName;
    }

    public JsonNode getInputJson() {
        return inputJson;
    }

    public JsonNode getOutputJson() {
        return outputJson;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
