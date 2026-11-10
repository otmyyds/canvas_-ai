package com.picturecreate.doccanvas.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
