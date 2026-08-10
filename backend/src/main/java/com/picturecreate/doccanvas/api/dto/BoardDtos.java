package com.picturecreate.doccanvas.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.picturecreate.doccanvas.domain.Board;
import com.picturecreate.doccanvas.domain.BoardVisibility;

public final class BoardDtos {

    private BoardDtos() {
    }

    public record BoardRequest(String title) {
    }

    public record BoardResponse(UUID id, String title, BoardVisibility visibility, Instant createdAt, Instant updatedAt) {
        public static BoardResponse from(Board board) {
            return new BoardResponse(board.getId(), board.getTitle(), board.getVisibility(), board.getCreatedAt(),
                    board.getUpdatedAt());
        }
    }
}
