package com.picturecreate.doccanvas.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/**
 * 所有实体共有的基类（@MappedSuperclass，不建表）。
 * 提供：id + createdAt + updatedAt 三个公共字段，所有表都有。
 * 时间戳由 @PrePersist/@PreUpdate 自动维护（新增/更新时刷新）。
 */
@MappedSuperclass
public abstract class BaseEntity {

    /** 主键（UUID，数据库自动生成） */
    @Id
    @GeneratedValue
    private UUID id;

    /** 创建时间（不可更新） */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 更新时间（每次保存自动刷新） */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 插入前自动设置创建/更新时间 */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    /** 更新前自动刷新更新时间 */
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
