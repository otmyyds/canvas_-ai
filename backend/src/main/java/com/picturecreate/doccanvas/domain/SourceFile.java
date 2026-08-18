package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * 上传资料文件实体，对应 source_files 表。
 * 文件本体存 RustFS（storageKey 指向），DB 记录元数据 + 解析结果。
 * 文本类文件（TXT/MD/PDF/DOCX）上传时自动解析为 textExcerpt，作为 AI 上下文。
 */
@Entity
@Table(name = "source_files")
public class SourceFile extends BaseEntity {

    /** 所属画布 id */
    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    /** 上传用户 id */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** RustFS 对象存储中的 key（定位文件本体） */
    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    /** 原始文件名 */
    @Column(nullable = false)
    private String filename;

    /** MIME 类型（text/plain, application/pdf 等） */
    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    /** 文件大小（字节） */
    @Column(nullable = false)
    private long size;

    /** 解析出的纯文本内容（TXT/MD 直接读，PDF/DOCX 提取后存这里） */
    @Column(name = "text_excerpt", columnDefinition = "text")
    private String textExcerpt;

    /** 解析状态：PENDING 待解析 / READY 已解析 / FAILED 失败 */
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
