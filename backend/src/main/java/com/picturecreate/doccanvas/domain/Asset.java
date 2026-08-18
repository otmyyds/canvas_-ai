package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 资产实体，对应 assets 表（预留）。
 * 计划用于 AI 生成图片等媒体资产（存 RustFS + 记录元数据）。
 * 当前 MVP 未实现生图流程，此表先建后用。
 */
@Entity
@Table(name = "assets")
public class Asset extends BaseEntity {

    /** 所有者用户 id */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** 所属画布 id */
    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    /** 产生此资产的任务 id（AI 生图来源） */
    @Column(name = "source_task_id")
    private UUID sourceTaskId;

    /** RustFS 存储桶 */
    @Column(name = "storage_bucket", nullable = false)
    private String storageBucket;

    /** RustFS 存储 key（定位文件本体） */
    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    /** MIME 类型 */
    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    /** 文件大小（字节） */
    @Column(nullable = false)
    private long size;

    /** 图片宽度（可选） */
    private Integer width;

    /** 图片高度（可选） */
    private Integer height;

    /** 文件校验和（去重/完整性） */
    private String checksum;

    protected Asset() {
    }
}
