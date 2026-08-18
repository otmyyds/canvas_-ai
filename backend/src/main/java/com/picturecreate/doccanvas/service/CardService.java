package com.picturecreate.doccanvas.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.CardRequest;
import com.picturecreate.doccanvas.domain.Card;
import com.picturecreate.doccanvas.domain.CardType;
import com.picturecreate.doccanvas.domain.BoardEdge;
import com.picturecreate.doccanvas.repository.BoardEdgeRepository;
import com.picturecreate.doccanvas.repository.CardRepository;

@Service
public class CardService {

    private static final Logger log = LoggerFactory.getLogger(CardService.class);

    private final BoardService boardService;
    private final CardRepository cardRepository;
    private final BoardEdgeRepository edgeRepository;

    public CardService(BoardService boardService, CardRepository cardRepository, BoardEdgeRepository edgeRepository) {
        this.boardService = boardService;
        this.cardRepository = cardRepository;
        this.edgeRepository = edgeRepository;
    }

    @Transactional(readOnly = true)
    public List<Card> list(UUID boardId, CurrentUser user) {
        boardService.getOwned(boardId, user);
        return cardRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
    }

    @Transactional
    public Card create(UUID boardId, CurrentUser user, CardRequest request) {
        boardService.getOwned(boardId, user);
        Card card = new Card(
                boardId,
                request.title(),
                request.contentMarkdown() == null ? "" : request.contentMarkdown(),
                request.cardType() == null ? CardType.NOTE : request.cardType(),
                request.x() == null ? 0 : request.x(),
                request.y() == null ? 0 : request.y(),
                request.width() == null ? 320 : request.width(),
                request.height() == null ? 180 : request.height());
        Card saved = cardRepository.save(card);
        log.info("卡片已创建：cardId={} boardId={} title={} type={}", saved.getId(), boardId, saved.getTitle(),
                saved.getCardType());
        return saved;
    }

    @Transactional
    public Card update(UUID cardId, CurrentUser user, CardRequest request) {
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new NotFoundException("卡片不存在"));
        boardService.getOwned(card.getBoardId(), user);
        card.update(request.title(), request.contentMarkdown(), request.cardType(), request.x(), request.y(),
                request.width(), request.height());
        log.info("卡片已更新：cardId={} title={}", cardId, card.getTitle());
        return card;
    }

    @Transactional
    public void delete(UUID cardId, CurrentUser user) {
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new NotFoundException("卡片不存在"));
        boardService.getOwned(card.getBoardId(), user);
        edgeRepository.deleteAll(edgeRepository.findByFromCardIdOrToCardId(cardId, cardId));
        cardRepository.delete(card);
        log.info("卡片已删除：cardId={} title={}", cardId, card.getTitle());
    }
}
