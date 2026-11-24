package com.picturecreate.doccanvas.service;

import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.domain.SourceFile;
import com.picturecreate.doccanvas.repository.SourceFileRepository;
import com.picturecreate.doccanvas.service.async.VectorizeStreamProducer;
import com.picturecreate.doccanvas.storage.ObjectStorageService;
import com.picturecreate.doccanvas.storage.ObjectStorageService.StoredObjectContent;
import com.picturecreate.doccanvas.storage.ObjectStorageService.StoredObject;

@Profile("!lite")
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final BoardService boardService;
    private final SourceFileRepository sourceFileRepository;
    private final ObjectStorageService objectStorageService;
    private final DocumentParseService documentParseService;
    private final VectorizeStreamProducer vectorizeStreamProducer;
    private final TransactionTemplate transactionTemplate;
    private final BoardEditingGuard editingGuard;

    public FileService(BoardService boardService, SourceFileRepository sourceFileRepository,
            ObjectStorageService objectStorageService,
            DocumentParseService documentParseService, VectorizeStreamProducer vectorizeStreamProducer,
            TransactionTemplate transactionTemplate, BoardEditingGuard editingGuard) {
        this.boardService = boardService;
        this.sourceFileRepository = sourceFileRepository;
        this.objectStorageService = objectStorageService;
        this.documentParseService = documentParseService;
        this.vectorizeStreamProducer = vectorizeStreamProducer;
        this.transactionTemplate = transactionTemplate;
        this.editingGuard = editingGuard;
    }

    @Transactional(readOnly = true)
    public List<SourceFile> list(UUID boardId, CurrentUser user) {
        boardService.getOwned(boardId, user);
        return sourceFileRepository.findByBoardIdAndOwnerIdOrderByCreatedAtDesc(boardId, user.id());
    }

    /**
     * 上传资料：RustFS 上传与 Tika 解析都在事务外，数据库只做一次 insert。
     *
     * 不加 @Transactional：S3 网络往返加大 PDF 解析合起来可达数秒，包进事务只会长时间占着数据库连接；
     * 上传成功而落库失败会留一个孤儿对象，比"记录指向一个不存在的对象"好，不额外加补偿。
     */
    public SourceFile upload(UUID boardId, CurrentUser user, MultipartFile file) throws IOException {
        boardService.getOwned(boardId, user);
        editingGuard.assertEditable(boardId);
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
        // 对象存储是外部网络 I/O，放在事务外
        StoredObject stored = objectStorageService.put(key, contentType, file.getSize(), file.getInputStream());
        SourceFile sourceFile = new SourceFile(boardId, user.id(), stored.key(), filename, contentType, stored.size());
        // 解析是 CPU 密集操作（大 PDF 数秒），同样在事务外，结果先写在内存对象上，随下面这一次 insert 一起落库
        boolean parsed = parseTextContent(sourceFile, file);
        // execute 的返回类型被标注为 @Nullable（Spring 无从得知回调一定会返回值），
        // 这里回调恒有返回值，requireNonNull 只是把这条不变式显式写出来
        SourceFile saved = Objects.requireNonNull(transactionTemplate.execute(status -> {
            SourceFile result = sourceFileRepository.save(sourceFile);
            if (parsed && result.getTextExcerpt() != null && !result.getTextExcerpt().isBlank()) {
                UUID fileId = result.getId();
                // 向量化投递放在提交之后：消费方必须先看到已提交的记录行，否则查询落空
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        vectorizeStreamProducer.send(fileId);
                    }
                });
            }
            return result;
        }));
        log.info("资料已上传：fileId={} filename={} size={} parsed={} status={}", saved.getId(), filename,
                stored.size(), parsed, saved.getParseStatus());
        return saved;
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

    /**
     * 取本人名下的资料，不存在即 404。
     * 只被本类的 delete / readContent 调用，故收成 private：类内自调用不走 Spring 代理，
     * 挂在这里的 @Transactional 一次都不会生效（实际运行在调用方的事务里），加了纯属误导。
     */
    private SourceFile getOwned(UUID fileId, CurrentUser user) {
        return sourceFileRepository.findByIdAndOwnerId(fileId, user.id())
                .orElseThrow(() -> new NotFoundException("文件不存在"));
    }

    /** 删除资料：先删记录并提交，提交后再清 RustFS 对象 */
    @Transactional
    public void delete(UUID fileId, CurrentUser user) {
        SourceFile file = getOwned(fileId, user);
        editingGuard.assertEditable(file.getBoardId());
        String storageKey = file.getStorageKey();
        // 记录删除并提交才是权威状态；向量 chunk 由 document_chunks 的 source_file_id 外键级联删除
        sourceFileRepository.delete(file);
        // 提交后再清对象存储。顺序不能反：对象删不掉只会留一个孤儿对象（浪费点存储），
        // 而"先删对象、记录删除失败回滚"会留下指向不存在对象的记录，前端就是一条 404 死链。
        // 这里也不能再往外抛异常——事务已经提交，抛出去只会让调用方误判成删除失败。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    objectStorageService.delete(storageKey);
                } catch (Exception exception) {
                    log.warn("清理对象存储失败，已留孤儿对象：key={} error={}", storageKey, exception.getMessage());
                }
            }
        });
        log.info("资料已删除：fileId={} storageKey={}", fileId, storageKey);
    }

    @Transactional(readOnly = true)
    public StoredObjectContent readContent(UUID fileId, CurrentUser user) {
        SourceFile file = getOwned(fileId, user);
        return objectStorageService.get(file.getStorageKey());
    }
}
