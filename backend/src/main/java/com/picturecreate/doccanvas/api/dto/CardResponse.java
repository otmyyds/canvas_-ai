package com.picturecreate.doccanvas.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.picturecreate.doccanvas.domain.Card;
import com.picturecreate.doccanvas.domain.CardType;

public record CardResponse(
        UUID id,
        UUID boardId,
        String title,
        String contentMarkdown,
        CardType cardType,
        double x,
        double y,
        double width,
        double height,
        Instant createdAt,
        Instant updatedAt) {

    public static CardResponse from(Card card) {
        return new CardResponse(card.getId(), card.getBoardId(), card.getTitle(), card.getContentMarkdown(),
                card.getCardType(), card.getX(), card.getY(), card.getWidth(), card.getHeight(), card.getCreatedAt(),
                card.getUpdatedAt());
    }
}
