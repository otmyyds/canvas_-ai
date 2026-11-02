package com.picturecreate.doccanvas.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Agent 会话，对应 agent_sessions 表。
 * 一个用户在一个画布下的持续对话；摘要用于压缩超出窗口的历史消息。
 */
@Entity
@Table(name = "agent_sessions")
public class AgentSession extends BaseEntity {

    /** 会话归属用户 */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** 会话所属画布 */
    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    /** 会话标题 */
    @Column(length = 255)
    private String title;

    /** 滚动摘要：超出窗口的早期消息压缩到这里 */
    @Column(columnDefinition = "text")
    private String summary;

    /** 最后活跃时间 */
    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt;

    protected AgentSession() {
    }

    public AgentSession(UUID ownerId, UUID boardId, String title) {
        this.ownerId = ownerId;
        this.boardId = boardId;
        this.title = title;
    }

    @PrePersist
    void initLastActiveAt() {
        if (lastActiveAt == null) {
            lastActiveAt = Instant.now();
        }
    }

    @PreUpdate
    void touchLastActiveAt() {
        lastActiveAt = Instant.now();
    }

    /** 更新滚动摘要并刷新活跃时间 */
    public void updateSummary(String summary) {
        this.summary = summary;
        this.lastActiveAt = Instant.now();
    }

    /** 仅刷新活跃时间：新消息写入但还没触发摘要时使用 */
    public void touch() {
        this.lastActiveAt = Instant.now();
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }
}
