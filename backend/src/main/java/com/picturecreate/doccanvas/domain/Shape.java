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

    /** 视觉属性 JSON（geo/color/fill/dash），纳管自由图形时保存，切回树形图时还原 */
    @Column(name = "visual_json")
    private String visualJson;

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

    public String getVisualJson() {
        return visualJson;
    }

    /** 更新节点坐标（思维导图扩展后整树重排时用） */
    public void update(double x, double y) {
        this.x = x;
        this.y = y;
    }

    /** 按标题估算结果放大尺寸：只增不减，保留用户手动放大的节点 */
    public void growTo(double width, double height) {
        if (width > this.width) {
            this.width = width;
        }
        if (height > this.height) {
            this.height = height;
        }
    }

    /** 精确设置尺寸：逻辑图 fork 节点按文字收缩，需要能变小 */
    public void resizeTo(double width, double height) {
        this.width = width;
        this.height = height;
    }

    /** 回写用户编辑：更新标题、位置与尺寸（标题为 null 时保持原值） */
    public void applyUserEdit(String title, double x, double y, double width, double height) {
        if (title != null) {
            this.title = title;
        }
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /** 保存视觉属性 JSON：传 null 表示清空（回到默认样式） */
    public void applyVisual(String visualJson) {
        this.visualJson = visualJson;
    }

    /** Agent 修改：只更新传入的非空字段，保留坐标和尺寸 */
    public void applyAgentEdit(String title, String shape, Integer level) {
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
        if (shape != null && !shape.isBlank()) {
            this.shape = shape;
        }
        if (level != null) {
            this.level = level;
        }
    }
}
