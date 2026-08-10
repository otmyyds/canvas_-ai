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

    /** 连线样式：curve=曲线 / straight=直线 */
    @Column
    private String style;

    /** 连线颜色（十六进制） */
    @Column
    private String color;

    /** 连线粗细 1-4 */
    @Column(nullable = false)
    private int weight = 2;

    protected BoardEdge() {
    }

    public BoardEdge(UUID boardId, UUID fromCardId, UUID toCardId, String label) {
        this(boardId, fromCardId, toCardId, label, null, null, 2);
    }

    public BoardEdge(UUID boardId, UUID fromCardId, UUID toCardId, String label, String style, String color,
            int weight) {
        this.boardId = boardId;
        this.fromCardId = fromCardId;
        this.toCardId = toCardId;
        this.label = label;
        this.style = style;
        this.color = color;
        this.weight = weight <= 0 ? 2 : weight;
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

    public String getStyle() {
        return style;
    }

    public String getColor() {
        return color;
    }

    public int getWeight() {
        return weight;
    }
}
