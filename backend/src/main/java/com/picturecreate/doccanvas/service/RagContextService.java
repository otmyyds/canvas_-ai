package com.picturecreate.doccanvas.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.picturecreate.doccanvas.domain.DocumentChunk;
import com.picturecreate.doccanvas.domain.SourceFile;
import com.picturecreate.doccanvas.repository.SourceFileRepository;
import com.picturecreate.doccanvas.service.ai.AiTaskContext.SourceContext;

/**
 * RAG 检索上下文服务。
 *
 * 从画布资料的向量索引（document_chunks + pgvector）检索与查询最相关的文本片段，
 * 替代「把全部已解析文档塞进 prompt」的做法，只注入 topK 个相关片段，
 * 避免超长上下文与 token 浪费，同时让模型聚焦于真正相关的内容。
 *
 * 供聊天（ChatService）与 AI 任务（AiTaskService.generate）共用，
 * 统一检索入口，检索为空时返回空列表（调用方按「无文档上下文」处理，不额外兜底）。
 */
@Service
public class RagContextService {

    /** 默认检索片段数 */
    private static final int DEFAULT_TOP_K = 5;

    private final DocumentChunkService chunkService;
    private final SourceFileRepository sourceFileRepository;

    public RagContextService(DocumentChunkService chunkService, SourceFileRepository sourceFileRepository) {
        this.chunkService = chunkService;
        this.sourceFileRepository = sourceFileRepository;
    }

    /**
     * 检索与 query 最相关的文档片段。
     *
     * @param boardId 画布 id（检索范围）
     * @param ownerId 所有者用户 id（检索范围）
     * @param query 检索查询（用户输入 / 任务提示词）
     * @param topK 返回片段数
     * @return (文件名, 片段内容) 列表；向量索引为空或无命中时返回空列表
     */
    @Transactional(readOnly = true)
    public List<SourceContext> retrieve(UUID boardId, UUID ownerId, String query, int topK) {
        List<DocumentChunk> chunks = chunkService.search(boardId, ownerId, query, Math.max(1, topK));
        if (chunks.isEmpty()) {
            return List.of();
        }
        // 一次查全部来源文件，映射 sourceFileId → 文件名，避免逐块查询
        Set<UUID> fileIds = chunks.stream().map(DocumentChunk::getSourceFileId).collect(Collectors.toSet());
        Map<UUID, String> filenames = sourceFileRepository.findAllById(fileIds).stream()
                .collect(Collectors.toMap(SourceFile::getId, SourceFile::getFilename));
        return chunks.stream()
                .map(chunk -> new SourceContext(
                        filenames.getOrDefault(chunk.getSourceFileId(), "资料片段"),
                        chunk.getContent()))
                .toList();
    }

    /** 使用默认 topK 检索 */
    public List<SourceContext> retrieve(UUID boardId, UUID ownerId, String query) {
        return retrieve(boardId, ownerId, query, DEFAULT_TOP_K);
    }
}
