package com.picturecreate.doccanvas.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.domain.SourceFile;
import com.picturecreate.doccanvas.repository.SourceFileRepository;
import com.picturecreate.doccanvas.storage.ObjectStorageService;
import com.picturecreate.doccanvas.storage.ObjectStorageService.StoredObjectContent;
import com.picturecreate.doccanvas.storage.ObjectStorageService.StoredObject;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final BoardService boardService;
    private final SourceFileRepository sourceFileRepository;
    private final ObjectStorageService objectStorageService;
    private final DocumentChunkService chunkService;
    private final DocumentParseService documentParseService;
    private final Executor vectorizeExecutor;

    public FileService(BoardService boardService, SourceFileRepository sourceFileRepository,
            ObjectStorageService objectStorageService, DocumentChunkService chunkService,
            DocumentParseService documentParseService, @Qualifier("aiTaskExecutor") Executor vectorizeExecutor) {
        this.boardService = boardService;
        this.sourceFileRepository = sourceFileRepository;
        this.objectStorageService = objectStorageService;
        this.chunkService = chunkService;
        this.documentParseService = documentParseService;
        this.vectorizeExecutor = vectorizeExecutor;
    }

    @Transactional(readOnly = true)
    public List<SourceFile> list(UUID boardId, CurrentUser user) {
        boardService.getOwned(boardId, user);
        return sourceFileRepository.findByBoardIdAndOwnerIdOrderByCreatedAtDesc(boardId, user.id());
    }

    @Transactional
    public SourceFile upload(UUID boardId, CurrentUser user, MultipartFile file) throws IOException {
        boardService.getOwned(boardId, user);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        String filename = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "未命名文件"
                : file.getOriginalFilename();
        String contentType = file.getContentType() == null || file.getContentType().isBlank()
                ? "application/octet-stream"
                : file.getContentType();
        String key = "users/%s/boards/%s/source-files/%s/%s".formatted(user.id(), boardId, UUID.randomUUID(),
                filename.replace("\\", "_").replace("/", "_"));
        StoredObject stored = objectStorageService.put(key, contentType, file.getSize(), file.getInputStream());
        SourceFile sourceFile = sourceFileRepository.save(new SourceFile(boardId, user.id(), stored.key(), filename, contentType, stored.size()));
        boolean parsed = parseTextContent(sourceFile, file);
        sourceFileRepository.save(sourceFile);
        log.info("资料已上传：fileId={} filename={} size={} parsed={} status={}", sourceFile.getId(), filename,
                stored.size(), parsed, sourceFile.getParseStatus());
        if (parsed && sourceFile.getTextExcerpt() != null && !sourceFile.getTextExcerpt().isBlank()) {
            UUID fileId = sourceFile.getId();
            UUID ownerId = user.id();
            String text = sourceFile.getTextExcerpt();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    vectorizeExecutor.execute(() -> chunkService.vectorize(fileId, boardId, ownerId, text));
                }
            });
        }
        return sourceFile;
    }

    /**
     * 解析上传文件为纯文本：
     * - TXT/Markdown：直接按 UTF-8 读取
     * - PDF/DOCX/DOC 等：用 Apache Tika 提取文本
     * - 其他类型：保持 PENDING
     * 返回是否解析成功。
     */
    private boolean parseTextContent(SourceFile sourceFile, MultipartFile file) {
        String filename = sourceFile.getFilename().toLowerCase();
        String mimeType = sourceFile.getMimeType();
        boolean textLike = filename.endsWith(".txt")
                || filename.endsWith(".md")
                || filename.endsWith(".markdown")
                || mimeType.startsWith("text/plain")
                || mimeType.contains("markdown")
                || mimeType.contains("text/markdown");
        boolean parseableBinary = filename.endsWith(".pdf")
                || filename.endsWith(".docx")
                || filename.endsWith(".doc")
                || filename.endsWith(".xlsx")
                || filename.endsWith(".xls")
                || filename.endsWith(".pptx")
                || mimeType.equals("application/pdf")
                || mimeType.contains("officedocument")
                || mimeType.contains("msword")
                || mimeType.contains("vnd.ms-excel");
        if (!textLike && !parseableBinary) {
            return false;
        }
        try {
            String text;
            if (textLike) {
                text = new String(file.getBytes(), StandardCharsets.UTF_8);
                // 去掉 UTF-8 BOM 与行尾空白，保留 Markdown 结构
                if (!text.isEmpty() && text.charAt(0) == '﻿') {
                    text = text.substring(1);
                }
            } else {
                String parsed = documentParseService.parse(file.getInputStream(), sourceFile.getFilename());
                if (parsed == null) {
                    sourceFile.markParseFailed();
                    return false;
                }
                text = parsed;
            }
            sourceFile.markParsed(text.trim());
            return true;
        } catch (IOException | RuntimeException exception) {
            sourceFile.markParseFailed();
            return false;
        }
    }

    @Transactional(readOnly = true)
    public SourceFile getOwned(UUID fileId, CurrentUser user) {
        return sourceFileRepository.findByIdAndOwnerId(fileId, user.id())
                .orElseThrow(() -> new NotFoundException("文件不存在"));
    }

    @Transactional
    public void delete(UUID fileId, CurrentUser user) {
        SourceFile file = getOwned(fileId, user);
        // 清理向量 chunk（document_chunks 有 source_file_id 外键级联删除）
        // 删除 RustFS 对象 + DB 记录
        objectStorageService.delete(file.getStorageKey());
        sourceFileRepository.delete(file);
        log.info("资料已删除：fileId={} storageKey={}", fileId, file.getStorageKey());
    }

    @Transactional(readOnly = true)
    public StoredObjectContent readContent(UUID fileId, CurrentUser user) {
        SourceFile file = getOwned(fileId, user);
        return objectStorageService.get(file.getStorageKey());
    }
}
