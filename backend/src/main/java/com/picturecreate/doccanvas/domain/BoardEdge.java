package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "board_edges")
public class BoardEdge extends BaseEntity {

    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    @Column(name = "from_card_id", nullable = false)
    private UUID fromCardId;

    @Column(name = "to_card_id", nullable = false)
    private UUID toCardId;

    @Column
    private String label;

    protected BoardEdge() {
    }

    public BoardEdge(UUID boardId, UUID fromCardId, UUID toCardId, String label) {
        this.boardId = boardId;
        this.fromCardId = fromCardId;
        this.toCardId = toCardId;
        this.label = label;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public UUID getFromCardId() {
        return fromCardId;
    }

    public UUID getToCardId() {
        return toCardId;
    }

    public String getLabel() {
        return label;
    }
}
