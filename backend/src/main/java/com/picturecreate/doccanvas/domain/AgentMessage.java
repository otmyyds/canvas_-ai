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
 * Agent 会话消息，对应 agent_messages 表。
 * 只保存用户可见内容、助手回复和工具结果摘要；完整工具输入输出在 agent_steps。
 */
@Entity
@Table(name = "agent_messages")
public class AgentMessage {

    /** 主键 */
    @Id
    @GeneratedValue
    private UUID id;

    /** 所属会话 */
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    /** 消息归属用户，查询必须带此条件 */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** 消息所属画布 */
    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    /** 消息角色 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentMessageRole role;

    /** 消息文本 */
    @Column(columnDefinition = "text")
    private String content;

    /** 工具名（工具摘要消息使用） */
    @Column(name = "tool_name", length = 128)
    private String toolName;

    /** 工具结果摘要 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_payload", columnDefinition = "jsonb")
    private JsonNode toolPayload;

    /** 关联的 AI 任务 */
    @Column(name = "task_id")
    private UUID taskId;

    /** 关联的 Agent 运行 */
    @Column(name = "run_id")
    private UUID runId;

    /** 创建时间（agent_messages 没有 updated_at，不继承 BaseEntity） */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AgentMessage() {
    }

    public AgentMessage(UUID sessionId, UUID ownerId, UUID boardId, AgentMessageRole role, String content,
            String toolName, JsonNode toolPayload, UUID taskId, UUID runId) {
        this.sessionId = sessionId;
        this.ownerId = ownerId;
        this.boardId = boardId;
        this.role = role;
        this.content = content;
        this.toolName = toolName;
        this.toolPayload = toolPayload;
        this.taskId = taskId;
        this.runId = runId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public AgentMessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getToolName() {
        return toolName;
    }

    public JsonNode getToolPayload() {
        return toolPayload;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getRunId() {
        return runId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
