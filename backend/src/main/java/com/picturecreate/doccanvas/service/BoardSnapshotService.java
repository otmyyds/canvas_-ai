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

    public BoardSnapshotService(BoardService boardService, BoardSnapshotRepository snapshotRepository) {
        this.boardService = boardService;
        this.snapshotRepository = snapshotRepository;
    }

    @Transactional(readOnly = true)
    public Optional<BoardSnapshot> latest(UUID boardId, CurrentUser user) {
        boardService.getOwned(boardId, user);
        return snapshotRepository.findFirstByBoardIdOrderByVersionDesc(boardId);
    }

    @Transactional
    public BoardSnapshot save(UUID boardId, CurrentUser user, String snapshotJson) {
        boardService.getOwned(boardId, user);
        int version = (int) snapshotRepository.countByBoardId(boardId) + 1;
        BoardSnapshot snapshot = snapshotRepository.save(new BoardSnapshot(boardId, snapshotJson, version));
        log.info("快照已保存：boardId={} version={} jsonLength={}", boardId, version,
                snapshotJson == null ? 0 : snapshotJson.length());
        return snapshot;
    }
}
