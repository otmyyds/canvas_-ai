package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 连线（edge）实体，对应 board_edges 表。
 * 连接两个节点（卡片或图形节点），表达关系/流程/层级。
 * 连线两端不强制引用 cards（V7 迁移删了外键），可连图形节点。
 */
@Entity
@Table(name = "board_edges")
public class BoardEdge extends BaseEntity {

    /** 所属画布 id */
    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    /** 连线起点节点 id（卡片或图形节点） */
    @Column(name = "from_card_id", nullable = false)
    private UUID fromCardId;

    /** 连线终点节点 id（卡片或图形节点） */
    @Column(name = "to_card_id", nullable = false)
    private UUID toCardId;

    /** 连线标签（如"是"/"否"/"参考"） */
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
