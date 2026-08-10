package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "board_snapshots")
public class BoardSnapshot extends BaseEntity {

    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "text")
    private String snapshotJson;

    @Column(nullable = false)
    private int version;

    protected BoardSnapshot() {
    }

    public BoardSnapshot(UUID boardId, String snapshotJson, int version) {
        this.boardId = boardId;
        this.snapshotJson = snapshotJson;
        this.version = version;
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
