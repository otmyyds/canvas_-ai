package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 计划级画布检查点，对应 board_checkpoints 表。
 * 在执行计划前抓取 shapes/edges/assets 全量行与 tldraw 快照，
 * 任一步失败或任务被中断时按原始 id 恢复画布；计划成功后删除。
 */
@Entity
@Table(name = "board_checkpoints")
public class BoardCheckpoint extends BaseEntity {

    /** 所属画布 id */
    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    /** 所属计划任务 id，每个任务最多一行 */
    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    /** 画布状态 JSON：shapes/edges/assets 行 + 最新 tldraw 快照 */
    @Column(name = "state_json", nullable = false, columnDefinition = "text")
    private String stateJson;

    protected BoardCheckpoint() {
    }

    public BoardCheckpoint(UUID boardId, UUID taskId, String stateJson) {
        this.boardId = boardId;
        this.taskId = taskId;
        this.stateJson = stateJson;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public String getStateJson() {
        return stateJson;
    }
}
