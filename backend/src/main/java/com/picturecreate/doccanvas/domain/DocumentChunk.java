package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "document_chunks")
public class DocumentChunk extends BaseEntity {

    @Column(name = "source_file_id", nullable = false)
    private UUID sourceFileId;

    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

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
