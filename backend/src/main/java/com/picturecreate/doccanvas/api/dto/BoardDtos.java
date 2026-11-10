package com.picturecreate.doccanvas.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.picturecreate.doccanvas.domain.Board;
import com.picturecreate.doccanvas.domain.BoardLayoutStyle;
import com.picturecreate.doccanvas.domain.BoardVisibility;

public final class BoardDtos {

    private BoardDtos() {
    }

    public record BoardRequest(String title) {
    }

    /** 切换思维导图排布风格：RADIAL 树形图 / LOGIC 逻辑图 */
    public record BoardLayoutRequest(String layoutStyle) {
    }

    public record BoardResponse(UUID id, String title, BoardVisibility visibility, BoardLayoutStyle layoutStyle,
            int radialMaxNodes, Instant createdAt, Instant updatedAt) {
        public static BoardResponse from(Board board, int radialMaxNodes) {
            return new BoardResponse(board.getId(), board.getTitle(), board.getVisibility(), board.getLayoutStyle(),
                    radialMaxNodes, board.getCreatedAt(), board.getUpdatedAt());
        }
    }

    /** 首页画布分页结果：page 从 1 开始，totalPages 为总页数 */
    public record BoardPageResponse(List<BoardResponse> items, int page, int size, long total, int totalPages) {
    }
}
