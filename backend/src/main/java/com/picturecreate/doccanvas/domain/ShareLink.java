package com.picturecreate.doccanvas.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 分享链接实体，对应 share_links 表。
 * 画布 owner 生成只读分享链接，凭 token 无需登录即可只读查看画布。
 */
@Entity
@Table(name = "share_links")
public class ShareLink extends BaseEntity {

    /** 被分享的画布 id */
    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    /** 生成分享链接的 owner */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** 分享 token（唯一，拼到 /share/{token} 访问） */
    @Column(nullable = false, unique = true)
    private String token;

    /** 权限（MVP 固定 READONLY） */
    @Column(nullable = false)
    private String permission;

    /** 过期时间（null 表示永不过期） */
    @Column(name = "expires_at")
    private Instant expiresAt;

    protected ShareLink() {
    }

    public ShareLink(UUID boardId, UUID ownerId, String token, String permission) {
        this.boardId = boardId;
        this.ownerId = ownerId;
        this.token = token;
        this.permission = permission;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getToken() {
        return token;
    }

    public String getPermission() {
        return permission;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
