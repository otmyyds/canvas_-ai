package com.picturecreate.doccanvas.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.domain.Asset;
import com.picturecreate.doccanvas.domain.Board;
import com.picturecreate.doccanvas.repository.AssetRepository;
import com.picturecreate.doccanvas.repository.BoardRepository;
import com.picturecreate.doccanvas.storage.ObjectStorageService;

@Service
public class BoardService {

    private static final Logger log = LoggerFactory.getLogger(BoardService.class);

    private final BoardRepository boardRepository;
    private final AssetRepository assetRepository;
    private final ObjectStorageService objectStorageService;
    private final BoardEditingGuard editingGuard;

    public BoardService(BoardRepository boardRepository, AssetRepository assetRepository,
            ObjectStorageService objectStorageService, BoardEditingGuard editingGuard) {
        this.boardRepository = boardRepository;
        this.assetRepository = assetRepository;
        this.objectStorageService = objectStorageService;
        this.editingGuard = editingGuard;
    }

    /**
     * 分页查询当前用户画布，按更新时间倒序。
     * page 从 1 开始；size 限制在 1~50，keyword 为空时查全部。
     */
    @Transactional(readOnly = true)
    public Page<Board> list(CurrentUser user, int page, int size, String keyword) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "updatedAt"));
        if (keyword == null || keyword.isBlank()) {
            return boardRepository.findByOwnerId(user.id(), pageable);
        }
        return boardRepository.findByOwnerIdAndTitleContainingIgnoreCase(user.id(), keyword.trim(), pageable);
    }

    @Transactional
    public Board create(CurrentUser user, String title) {
        String safeTitle = title == null || title.isBlank() ? "未命名画布" : title.trim();
        Board board = boardRepository.save(new Board(user.id(), safeTitle));
        log.info("画布已创建：boardId={} title={} ownerId={}", board.getId(), safeTitle, user.id());
        return board;
    }

    @Transactional(readOnly = true)
    public Board getOwned(UUID boardId, CurrentUser user) {
        return boardRepository.findByIdAndOwnerId(boardId, user.id())
                .orElseThrow(() -> new NotFoundException("画布不存在"));
    }

    @Transactional
    public Board rename(UUID boardId, CurrentUser user, String title) {
        Board board = getOwned(boardId, user);
        editingGuard.assertEditable(boardId);
        board.rename(title == null || title.isBlank() ? board.getTitle() : title.trim());
        return board;
    }

    @Transactional
    public void delete(UUID boardId, CurrentUser user) {
        Board board = getOwned(boardId, user);
        editingGuard.assertEditable(boardId);
        List<String> assetKeys = assetRepository
                .findByBoardIdAndOwnerIdOrderByCreatedAtAsc(boardId, user.id())
                .stream()
                .map(Asset::getStorageKey)
                .toList();
        String coverKey = board.getCoverKey();
        String imagePrefix = "images/%s/%s/".formatted(user.id(), boardId);
        String coverPrefix = "covers/%s/".formatted(boardId);
        // 兼容历史 AI 图片/资料对象：users/{owner}/boards/{board}/...
        String legacyPrefix = "users/%s/boards/%s/".formatted(user.id(), boardId);
        boardRepository.delete(board);
        // 数据库记录提交后再删本地/对象存储文件，避免回滚后文件已丢失。
        // 前缀删除会连同历史上没有被数据库引用的旧封面/旧图片一起清理。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String key : assetKeys) {
                    try {
                        objectStorageService.delete(key);
                    } catch (Exception exception) {
                        log.warn("删除画布图片文件失败：key={} error={}", key, exception.getMessage());
                    }
                }
                try {
                    objectStorageService.deletePrefix(imagePrefix);
                    objectStorageService.deletePrefix(coverPrefix);
                    objectStorageService.deletePrefix(legacyPrefix);
                } catch (Exception exception) {
                    log.warn("删除画布存储前缀失败：imagePrefix={} coverPrefix={} legacyPrefix={} error={}",
                            imagePrefix, coverPrefix, legacyPrefix, exception.getMessage());
                }
            }
        });
        log.info("画布已删除：boardId={} title={} 图片={} 封面={}", boardId, board.getTitle(), assetKeys.size(),
                coverKey != null);
    }
}
