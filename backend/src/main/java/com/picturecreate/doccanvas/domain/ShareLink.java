package com.picturecreate.doccanvas.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "share_links")
public class ShareLink extends BaseEntity {

    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(nullable = false)
    private String permission;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected ShareLink() {
    }
}
