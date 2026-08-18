package com.picturecreate.doccanvas.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.domain.Board;
import com.picturecreate.doccanvas.repository.BoardRepository;

@Service
public class BoardService {

    private static final Logger log = LoggerFactory.getLogger(BoardService.class);

    private final BoardRepository boardRepository;

    public BoardService(BoardRepository boardRepository) {
        this.boardRepository = boardRepository;
    }

    @Transactional(readOnly = true)
    public List<Board> list(CurrentUser user) {
        return boardRepository.findByOwnerIdOrderByUpdatedAtDesc(user.id());
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
        board.rename(title == null || title.isBlank() ? board.getTitle() : title.trim());
        return board;
    }

    @Transactional
    public void delete(UUID boardId, CurrentUser user) {
        Board board = getOwned(boardId, user);
        boardRepository.delete(board);
        log.info("画布已删除：boardId={} title={}", boardId, board.getTitle());
    }
}
