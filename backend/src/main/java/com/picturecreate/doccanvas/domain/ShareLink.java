package com.picturecreate.doccanvas.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 分享链接实体，对应 share_links 表（预留）。
 * 计划用于"只读分享画布"：token 唯一标识分享链接，permission 控制权限，expiresAt 过期时间。
 * 当前 MVP 未实现分享功能，此表先建后用。
 */
@Entity
@Table(name = "share_links")
public class ShareLink extends BaseEntity {

    /** 被分享的画布 id */
    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    /** 分享者用户 id */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** 分享 token（唯一，URL 用） */
    @Column(nullable = false, unique = true)
    private String token;

    /** 权限：readonly 等 */
    @Column(nullable = false)
    private String permission;

    /** 过期时间（空 = 永久） */
    @Column(name = "expires_at")
    private Instant expiresAt;

    protected ShareLink() {
    }
}
