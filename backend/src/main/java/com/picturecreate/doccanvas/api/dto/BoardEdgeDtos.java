package com.picturecreate.doccanvas.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.picturecreate.doccanvas.domain.BoardEdge;

public final class BoardEdgeDtos {

    private BoardEdgeDtos() {
    }

    public record BoardEdgeResponse(
            UUID id,
            UUID boardId,
            UUID fromShapeId,
            UUID toShapeId,
            String label,
            String style,
            String color,
            Integer weight,
            Integer sortOrder,
            String side,
            Instant createdAt) {

        public static BoardEdgeResponse from(BoardEdge edge) {
            return new BoardEdgeResponse(edge.getId(), edge.getBoardId(), edge.getFromShapeId(), edge.getToShapeId(),
                    edge.getLabel(), edge.getStyle(), edge.getColor(), edge.getWeight(), edge.getSortOrder(),
                    edge.getSide(), edge.getCreatedAt());
        }
    }
}
