package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "assets")
public class Asset extends BaseEntity {

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    @Column(name = "source_task_id")
    private UUID sourceTaskId;

    @Column(name = "storage_bucket", nullable = false)
    private String storageBucket;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(nullable = false)
    private long size;

    private Integer width;

    private Integer height;

    private String checksum;

    protected Asset() {
    }
}
