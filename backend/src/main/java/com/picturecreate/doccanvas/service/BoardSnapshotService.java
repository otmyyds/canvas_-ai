package com.picturecreate.doccanvas.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.domain.BoardSnapshot;
import com.picturecreate.doccanvas.repository.BoardSnapshotRepository;

@Service
public class BoardSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(BoardSnapshotService.class);

    private final BoardService boardService;
    private final BoardSnapshotRepository snapshotRepository;
    private final BoardEditingGuard editingGuard;

    public BoardSnapshotService(BoardService boardService, BoardSnapshotRepository snapshotRepository,
            BoardEditingGuard editingGuard) {
        this.boardService = boardService;
        this.snapshotRepository = snapshotRepository;
        this.editingGuard = editingGuard;
    }

    @Transactional(readOnly = true)
    public Optional<BoardSnapshot> latest(UUID boardId, CurrentUser user) {
        boardService.getOwned(boardId, user);
        return snapshotRepository.findFirstByBoardIdOrderByCreatedAtDesc(boardId);
    }

    @Transactional
    public BoardSnapshot save(UUID boardId, CurrentUser user, String snapshotJson) {
        boardService.getOwned(boardId, user);
        editingGuard.assertEditable(boardId);
        // 单行快照：已有则覆盖 JSON 并递增版本，只有首次保存才插入新行
        BoardSnapshot snapshot = snapshotRepository.findByBoardId(boardId)
                .map(existing -> {
                    existing.update(snapshotJson);
                    return existing;
                })
                .orElseGet(() -> new BoardSnapshot(boardId, snapshotJson, 1));
        BoardSnapshot saved = snapshotRepository.save(snapshot);
        log.info("快照已保存：boardId={} version={} jsonLength={}", boardId, saved.getVersion(),
                snapshotJson == null ? 0 : snapshotJson.length());
        return saved;
    }
}
