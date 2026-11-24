package com.picturecreate.doccanvas.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.picturecreate.doccanvas.domain.Board;
import com.picturecreate.doccanvas.repository.BoardRepository;
import com.picturecreate.doccanvas.storage.ObjectStorageService;
import com.picturecreate.doccanvas.storage.ObjectStorageService.StoredObject;
import com.picturecreate.doccanvas.storage.ObjectStorageService.StoredObjectContent;

/**
 * 画布封面：前端把画布导出成缩略图上传，后端重新编码并保存。
 * 封面独立存储，不进入 assets 列表，避免出现在画布图片中。
 */
@Service
public class BoardCoverService {

    private static final Logger log = LoggerFactory.getLogger(BoardCoverService.class);
    private static final long MAX_COVER_BYTES = 1024 * 1024;

    private final BoardService boardService;
    private final BoardRepository boardRepository;
    private final ObjectStorageService objectStorageService;

    public BoardCoverService(BoardService boardService, BoardRepository boardRepository,
            ObjectStorageService objectStorageService) {
        this.boardService = boardService;
        this.boardRepository = boardRepository;
        this.objectStorageService = objectStorageService;
    }

    @Transactional
    public Board updateCover(UUID boardId, CurrentUser user, MultipartFile file) {
        Board board = boardService.getOwned(boardId, user);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择封面图片");
        }
        if (file.getSize() > MAX_COVER_BYTES) {
            throw new IllegalArgumentException("封面图片不能超过 1MB");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.equals("image/png") && !contentType.equals("image/jpeg")
                && !contentType.equals("image/jpg")) {
            throw new IllegalArgumentException("封面只支持 PNG 或 JPEG 图片");
        }

        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
        } catch (IOException exception) {
            throw new IllegalArgumentException("封面图片无法识别", exception);
        }
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            throw new IllegalArgumentException("封面图片无法识别");
        }
        if (image.getWidth() > 1280 || image.getHeight() > 800) {
            throw new IllegalArgumentException("封面尺寸不能超过 1280x800");
        }

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", encoded)) {
                throw new IllegalStateException("封面图片编码失败");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("封面图片编码失败", exception);
        }
        byte[] bytes = encoded.toByteArray();
        if (bytes.length > MAX_COVER_BYTES) {
            throw new IllegalArgumentException("封面处理后仍然超过 1MB");
        }

        String key = "covers/%s/%s.png".formatted(boardId, UUID.randomUUID());
        StoredObject stored;
        try {
            stored = objectStorageService.put(key, "image/png", bytes.length,
                    new ByteArrayInputStream(bytes));
        } catch (IOException exception) {
            throw new IllegalStateException("存储封面失败：" + exception.getMessage(), exception);
        }
        board.updateCover(stored.key(), stored.contentType());
        boardRepository.save(board);
        // 只清理 60 秒前的旧对象：同一画布并发上传时，另一路刚写进来的文件还没提交，
        // 无条件按前缀删会把它误删，导致对方提交后指向一个已经不存在的 key。
        deletePrefixAfterCommit(coverPrefix(boardId), stored.key(), Instant.now().minusSeconds(60));
        log.info("画布封面已更新：boardId={} key={} version={}", boardId, stored.key(), board.getCoverVersion());
        return board;
    }

    @Transactional(readOnly = true)
    public StoredObjectContent readCover(UUID boardId, CurrentUser user) {
        Board board = boardService.getOwned(boardId, user);
        if (board.getCoverKey() == null || board.getCoverKey().isBlank()) {
            throw new NotFoundException("画布还没有封面");
        }
        return objectStorageService.get(board.getCoverKey());
    }

    @Transactional
    public void deleteCover(UUID boardId, CurrentUser user) {
        Board board = boardService.getOwned(boardId, user);
        board.clearCover();
        boardRepository.save(board);
        deletePrefixAfterCommit(coverPrefix(boardId), null);
        log.info("画布封面已删除：boardId={}", boardId);
    }

    private String coverPrefix(UUID boardId) {
        return "covers/%s/".formatted(boardId);
    }

    private void deletePrefixAfterCommit(String prefix, String keepKey) {
        deletePrefixAfterCommit(prefix, keepKey, null);
    }

    private void deletePrefixAfterCommit(String prefix, String keepKey, Instant olderThan) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    objectStorageService.deletePrefixExcept(prefix, keepKey, olderThan);
                } catch (Exception exception) {
                    log.warn("清理封面文件失败：prefix={} keep={} error={}",
                            prefix, keepKey, exception.getMessage());
                }
            }
        });
    }
}
