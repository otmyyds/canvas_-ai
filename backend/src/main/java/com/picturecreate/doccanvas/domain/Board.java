package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * 画布（board）实体，对应 boards 表。
 * 画布是内容的顶层容器：包含图形节点、连线、快照、资料。
 * 数据按 owner 隔离（每张画布只有一个所有者）。
 */
@Entity
@Table(name = "boards")
public class Board extends BaseEntity {

    /** 画布所有者用户 id（数据隔离依据） */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** 画布标题（默认"未命名画布"） */
    @Column(nullable = false)
    private String title;

    /** 可见性：PRIVATE 私有 / SHARED_READONLY 只读分享（默认私有） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BoardVisibility visibility = BoardVisibility.PRIVATE;

    /** 思维导图排布风格（默认树形图；节点过多时只能使用逻辑图） */
    @Enumerated(EnumType.STRING)
    @Column(name = "layout_style", nullable = false, length = 16)
    private BoardLayoutStyle layoutStyle = BoardLayoutStyle.RADIAL;

    protected Board() {
    }

    public Board(UUID ownerId, String title) {
        this.ownerId = ownerId;
        this.title = title;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public BoardVisibility getVisibility() {
        return visibility;
    }

    public BoardLayoutStyle getLayoutStyle() {
        return layoutStyle;
    }

    public void changeLayoutStyle(BoardLayoutStyle layoutStyle) {
        this.layoutStyle = layoutStyle;
    }

    public void rename(String title) {
        this.title = title;
    }

    public void setVisibility(BoardVisibility visibility) {
        this.visibility = visibility;
    }
}
