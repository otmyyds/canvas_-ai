package com.picturecreate.doccanvas.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.picturecreate.doccanvas.domain.BoardEdge;

import jakarta.validation.constraints.NotNull;

public final class BoardEdgeDtos {

    private BoardEdgeDtos() {
    }

    public record BoardEdgeRequest(
            @NotNull(message = "起点卡片不能为空") UUID fromCardId,
            @NotNull(message = "终点卡片不能为空") UUID toCardId,
            String label) {
    }

    public record BoardEdgeResponse(
            UUID id,
            UUID boardId,
            UUID fromCardId,
            UUID toCardId,
            String label,
            Instant createdAt) {

        public static BoardEdgeResponse from(BoardEdge edge) {
            return new BoardEdgeResponse(edge.getId(), edge.getBoardId(), edge.getFromCardId(), edge.getToCardId(),
                    edge.getLabel(), edge.getCreatedAt());
        }
    }
}
