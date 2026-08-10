package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.picturecreate.doccanvas.domain.DocumentChunk;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findBySourceFileIdOrderByChunkIndexAsc(UUID sourceFileId);

    @Modifying
    @Query("delete from DocumentChunk c where c.sourceFileId = :sourceFileId")
    void deleteBySourceFileId(@Param("sourceFileId") UUID sourceFileId);

    /**
     * 用原生 SQL 插入带 embedding 的 chunk。
     * pgvector 的 vector 列不能用 JPA 的 String 绑定，需 cast(:embedding as vector)。
     */
    @Modifying
    @Query(value = """
            insert into document_chunks (id, source_file_id, board_id, owner_id, chunk_index, content, embedding, created_at, updated_at)
            values (:id, :sourceFileId, :boardId, :ownerId, :chunkIndex, :content, cast(:embedding as vector), now(), now())
            """, nativeQuery = true)
    void insertWithEmbedding(@Param("id") UUID id, @Param("sourceFileId") UUID sourceFileId,
            @Param("boardId") UUID boardId, @Param("ownerId") UUID ownerId, @Param("chunkIndex") int chunkIndex,
            @Param("content") String content, @Param("embedding") String embedding);

    /**
     * pgvector 余弦相似度检索。原生查询只取 id（embedding 是 vector 类型，避免实体映射问题），
     * 调用方再用 id 加载实体。
     */
    @Query(value = """
            select id
            from document_chunks
            where board_id = :boardId and owner_id = :ownerId
            order by embedding <=> cast(:embedding as vector) asc
            limit :topK
            """, nativeQuery = true)
    List<UUID> searchIdsByVector(@Param("boardId") UUID boardId, @Param("ownerId") UUID ownerId,
            @Param("embedding") String embedding, @Param("topK") int topK);
}
