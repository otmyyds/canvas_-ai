package com.picturecreate.doccanvas.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.domain.BoardEdge;
import com.picturecreate.doccanvas.repository.BoardEdgeRepository;

@Service
public class BoardEdgeService {

    private static final Logger log = LoggerFactory.getLogger(BoardEdgeService.class);

    private final BoardService boardService;
    private final BoardEdgeRepository edgeRepository;

    public BoardEdgeService(BoardService boardService, BoardEdgeRepository edgeRepository) {
        this.boardService = boardService;
        this.edgeRepository = edgeRepository;
    }

    @Transactional(readOnly = true)
    public List<BoardEdge> list(UUID boardId, CurrentUser user) {
        boardService.getOwned(boardId, user);
        log.info("查询连线列表：boardId={}", boardId);
        return edgeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
    }
}
