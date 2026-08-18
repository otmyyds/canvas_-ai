package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 文档分块实体，对应 document_chunks 表（RAG 向量索引）。
 * 上传的文本资料被切分成多个 chunk，每个 chunk 计算 embedding 存入 pgvector，
 * 供 AI 任务做相似度检索（search_document_chunks）。
 */
@Entity
@Table(name = "document_chunks")
public class DocumentChunk extends BaseEntity {

    /** 来源资料文件 id（关联 source_files） */
    @Column(name = "source_file_id", nullable = false)
    private UUID sourceFileId;

    /** 所属画布 id */
    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    /** 所有者用户 id */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** 块序号（同一文件内从 0 开始） */
    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    /** 块文本内容 */
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** 向量 embedding（pgvector vector(1024)，存文本形式 "[0.1,0.2,...]"） */
    @Column(columnDefinition = "vector(1024)")
    private String embedding;

    protected DocumentChunk() {
    }

    public DocumentChunk(UUID sourceFileId, UUID boardId, UUID ownerId, int chunkIndex, String content) {
        this.sourceFileId = sourceFileId;
        this.boardId = boardId;
        this.ownerId = ownerId;
        this.chunkIndex = chunkIndex;
        this.content = content;
    }

    public void setEmbedding(String embedding) {
        this.embedding = embedding;
    }

    public UUID getSourceFileId() {
        return sourceFileId;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public String getEmbedding() {
        return embedding;
    }
}
