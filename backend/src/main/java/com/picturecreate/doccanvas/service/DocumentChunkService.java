package com.picturecreate.doccanvas.service;

import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.picturecreate.doccanvas.domain.DocumentChunk;
import com.picturecreate.doccanvas.repository.DocumentChunkRepository;
import com.picturecreate.doccanvas.repository.SourceFileRepository;
import com.picturecreate.doccanvas.service.ai.EmbeddingClient;

/**
 * 文档分块、向量化与检索。
 * 解析后的纯文本 → 按自然段落/固定长度切 chunk → 批量 embedding → 写 document_chunks。
 */
@Profile("!lite")
@Service
public class DocumentChunkService {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunkService.class);

    /** 单块目标字符数 */
    private static final int CHUNK_SIZE = 800;
    /** 块间重叠字符数 */
    private static final int CHUNK_OVERLAP = 100;
    /** embedding API 单批最大条数（百炼限制 10） */
    private static final int EMBED_BATCH_SIZE = 10;

    private final DocumentChunkRepository chunkRepository;
    private final SourceFileRepository sourceFileRepository;
    private final EmbeddingClient embeddingClient;

    public DocumentChunkService(DocumentChunkRepository chunkRepository, SourceFileRepository sourceFileRepository,
            EmbeddingClient embeddingClient) {
        this.chunkRepository = chunkRepository;
        this.sourceFileRepository = sourceFileRepository;
        this.embeddingClient = embeddingClient;
    }

    /**
     * 对某个文件的已解析文本做分块 + 向量化 + 落库。
     * 失败不抛出（向量化是增强能力，不阻断上传/任务主流程）。
     */
    @Transactional
    public void vectorize(UUID sourceFileId, UUID boardId, UUID ownerId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        // 文件可能在上传后、向量化前被删除（异步竞态），跳过避免外键冲突
        if (!sourceFileRepository.existsById(sourceFileId)) {
            log.info("文件已删除，跳过向量化：fileId={}", sourceFileId);
            return;
        }
        chunkRepository.deleteBySourceFileId(sourceFileId);
        List<String> chunks = split(text);
        // 百炼 embedding API 单批最多 10 条，分批调用
        List<String> vectors = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i += EMBED_BATCH_SIZE) {
            int end = Math.min(i + EMBED_BATCH_SIZE, chunks.size());
            vectors.addAll(embeddingClient.embedAll(chunks.subList(i, end)));
        }
        if (vectors.size() != chunks.size()) {
            log.warn("embedding 结果数量与 chunk 不一致（{} vs {}），放弃向量化", vectors.size(), chunks.size());
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            chunkRepository.insertWithEmbedding(UUID.randomUUID(), sourceFileId, boardId, ownerId, i, chunks.get(i),
                    vectors.get(i));
        }
        log.info("文档已向量化：fileId={}, chunks={}", sourceFileId, chunks.size());
    }

    /** 按固定长度切块，保留自然换行边界 */
    List<String> split(String text) {
        List<String> result = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n");
        int length = normalized.length();
        int start = 0;
        while (start < length) {
            int end = Math.min(start + CHUNK_SIZE, length);
            // 尽量在换行处断开，避免单词/句子被截断
            if (end < length) {
                int newline = normalized.lastIndexOf('\n', end);
                if (newline > start && end - newline < CHUNK_SIZE / 2) {
                    end = newline;
                }
            }
            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                result.add(chunk);
            }
            if (end >= length) {
                break;
            }
            start = Math.max(start + 1, end - CHUNK_OVERLAP);
        }
        return result;
    }

    /** 相似度检索，返回命中的 chunk 文本列表 */
    @Transactional(readOnly = true)
    public List<DocumentChunk> search(UUID boardId, UUID ownerId, String query, int topK) {
        String queryVector = embeddingClient.embed(query);
        if (queryVector == null) {
            return List.of();
        }
        List<UUID> ids = chunkRepository.searchIdsByVector(boardId, ownerId, queryVector, Math.max(1, topK));
        if (ids.isEmpty()) {
            return List.of();
        }
        return chunkRepository.findAllById(ids);
    }
}
