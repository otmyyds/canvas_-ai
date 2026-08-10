package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "source_files")
public class SourceFile extends BaseEntity {

    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(nullable = false)
    private String filename;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(nullable = false)
    private long size;

    @Column(name = "text_excerpt", columnDefinition = "text")
    private String textExcerpt;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false)
    private FileParseStatus parseStatus = FileParseStatus.PENDING;

    protected SourceFile() {
    }

    public SourceFile(UUID boardId, UUID ownerId, String storageKey, String filename, String mimeType, long size) {
        this.boardId = boardId;
        this.ownerId = ownerId;
        this.storageKey = storageKey;
        this.filename = filename;
        this.mimeType = mimeType;
        this.size = size;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getFilename() {
        return filename;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSize() {
        return size;
    }

    public String getTextExcerpt() {
        return textExcerpt;
    }

    public FileParseStatus getParseStatus() {
        return parseStatus;
    }

    /** 解析成功：把纯文本内容写入 text_excerpt 并置为 READY */
    public void markParsed(String parsedText) {
        this.textExcerpt = parsedText;
        this.parseStatus = FileParseStatus.READY;
    }

    /** 解析失败 */
    public void markParseFailed() {
        this.parseStatus = FileParseStatus.FAILED;
    }
}
