package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 画布快照实体，对应 board_snapshots 表。
 * 存 tldraw 完整画布状态（所有 shape/asset），用户编辑防抖保存。
 * 刷新时优先加载快照恢复画布；version 用于版本递增。
 */
@Entity
@Table(name = "board_snapshots")
@DynamicUpdate
public class BoardSnapshot extends BaseEntity {

    /** 所属画布 id */
    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    /** tldraw 序列化快照 JSON（完整画布状态） */
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "text")
    private String snapshotJson;

    /** 快照版本号（每次保存递增） */
    @Column(nullable = false)
    private int version;

    protected BoardSnapshot() {
    }

    public BoardSnapshot(UUID boardId, String snapshotJson, int version) {
        this.boardId = boardId;
        this.snapshotJson = snapshotJson;
        this.version = version;
    }

    /** 单行快照更新：覆盖 JSON 并递增版本，不再追加历史行 */
    public void update(String snapshotJson) {
        this.snapshotJson = snapshotJson;
        this.version++;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public int getVersion() {
        return version;
    }
}
