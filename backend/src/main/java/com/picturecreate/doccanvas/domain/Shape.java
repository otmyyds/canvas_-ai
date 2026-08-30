package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 图形节点（shape）实体，对应 shapes 表。
 * 思维导图/流程图的节点，是画布唯一的内容节点类型。
 * 有类型（ellipse/rectangle/oval）+ 层级（level）+ 位置尺寸。
 * 由 AI 任务 create_shape 动作生成。
 */
@Entity
@Table(name = "shapes")
public class Shape extends BaseEntity {

    /** 所属画布 id */
    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    /** 节点标题（显示在图形上） */
    @Column(nullable = false)
    private String title;

    /** 图形类型：ellipse 椭圆 / rectangle 矩形 / oval 胶囊 / diamond 菱形（流程图判断节点） */
    @Column(nullable = false)
    private String shape;

    /** 层级：0=中心主题，1=一级分支，2=二级叶子 */
    @Column(nullable = false)
    private int level;

    /** 画布 x 坐标 */
    @Column(nullable = false)
    private double x;

    /** 画布 y 坐标 */
    @Column(nullable = false)
    private double y;

    /** 节点宽度 */
    @Column(nullable = false)
    private double width;

    /** 节点高度 */
    @Column(nullable = false)
    private double height;

    protected Shape() {
    }

    public Shape(UUID boardId, String title, String shape, int level, double x, double y, double width,
            double height) {
        this.boardId = boardId;
        this.title = title;
        this.shape = shape;
        this.level = level;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public String getTitle() {
        return title;
    }

    public String getShape() {
        return shape;
    }

    public int getLevel() {
        return level;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

    /** 更新节点坐标（思维导图扩展后整树重排时用） */
    public void update(double x, double y) {
        this.x = x;
        this.y = y;
    }
}
