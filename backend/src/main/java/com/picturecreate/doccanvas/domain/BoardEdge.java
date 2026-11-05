package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 连线（edge）实体，对应 board_edges 表。
 * 连接两个图形节点，表达关系/流程/层级。
 * 连线两端不强制外键（V7 迁移删了外键），归属由应用层校验。
 */
@Entity
@Table(name = "board_edges")
public class BoardEdge extends BaseEntity {

    /** 所属画布 id */
    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    /** 连线起点图形节点 id */
    @Column(name = "from_shape_id", nullable = false)
    private UUID fromShapeId;

    /** 连线终点图形节点 id */
    @Column(name = "to_shape_id", nullable = false)
    private UUID toShapeId;

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

    /** 同一父节点下的兄弟顺序：布局按它排序，0 表示未手动排序 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /** 逻辑图一级分支的左右侧：LEFT / RIGHT，NULL 表示按两侧高度自动分配 */
    @Column(length = 8)
    private String side;

    protected BoardEdge() {
    }

    public BoardEdge(UUID boardId, UUID fromShapeId, UUID toShapeId, String label) {
        this(boardId, fromShapeId, toShapeId, label, null, null, 2);
    }

    public BoardEdge(UUID boardId, UUID fromShapeId, UUID toShapeId, String label, String style, String color,
            int weight) {
        this.boardId = boardId;
        this.fromShapeId = fromShapeId;
        this.toShapeId = toShapeId;
        this.label = label;
        this.style = style;
        this.color = color;
        this.weight = weight <= 0 ? 2 : weight;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public UUID getFromShapeId() {
        return fromShapeId;
    }

    public UUID getToShapeId() {
        return toShapeId;
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

    public int getSortOrder() {
        return sortOrder;
    }

    public String getSide() {
        return side;
    }

    /** 更新连线端点（连线重定向用） */
    public void updateEndpoints(UUID fromShapeId, UUID toShapeId) {
        this.fromShapeId = fromShapeId;
        this.toShapeId = toShapeId;
    }

    /** 设置同级顺序与逻辑图分侧；side 仅一级分支使用，其余传 null */
    public void reorder(int sortOrder, String side) {
        this.sortOrder = Math.max(sortOrder, 0);
        this.side = side;
    }
}
