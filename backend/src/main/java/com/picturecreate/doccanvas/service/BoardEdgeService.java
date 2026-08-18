package com.picturecreate.doccanvas.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.domain.BoardEdge;
import com.picturecreate.doccanvas.domain.Card;
import com.picturecreate.doccanvas.repository.BoardEdgeRepository;
import com.picturecreate.doccanvas.repository.CardRepository;

@Service
public class BoardEdgeService {

    private static final Logger log = LoggerFactory.getLogger(BoardEdgeService.class);

    private final BoardService boardService;
    private final CardRepository cardRepository;
    private final BoardEdgeRepository edgeRepository;

    public BoardEdgeService(BoardService boardService, CardRepository cardRepository, BoardEdgeRepository edgeRepository) {
        this.boardService = boardService;
        this.cardRepository = cardRepository;
        this.edgeRepository = edgeRepository;
    }

    @Transactional(readOnly = true)
    public List<BoardEdge> list(UUID boardId, CurrentUser user) {
        boardService.getOwned(boardId, user);
        return edgeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
    }

    @Transactional
    public BoardEdge create(UUID boardId, CurrentUser user, UUID fromCardId, UUID toCardId, String label) {
        boardService.getOwned(boardId, user);
        if (fromCardId.equals(toCardId)) {
            throw new IllegalStateException("不能连接同一张卡片");
        }
        Card from = cardRepository.findById(fromCardId).orElseThrow(() -> new NotFoundException("起点卡片不存在"));
        Card to = cardRepository.findById(toCardId).orElseThrow(() -> new NotFoundException("终点卡片不存在"));
        if (!from.getBoardId().equals(boardId) || !to.getBoardId().equals(boardId)) {
            throw new IllegalStateException("只能连接当前画布内的卡片");
        }
        String safeLabel = label == null || label.isBlank() ? null : label.trim();
        BoardEdge edge = edgeRepository.save(new BoardEdge(boardId, fromCardId, toCardId, safeLabel));
        log.info("连线已创建：edgeId={} boardId={} from={} to={} label={}", edge.getId(), boardId, fromCardId,
                toCardId, safeLabel);
        return edge;
    }
}
