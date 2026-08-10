package com.picturecreate.doccanvas.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.picturecreate.doccanvas.domain.Board;
import com.picturecreate.doccanvas.repository.BoardRepository;

@Service
public class BoardService {

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
        return boardRepository.save(new Board(user.id(), safeTitle));
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
    }
}
