package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "shapes")
public class Shape extends BaseEntity {

    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String shape;

    @Column(nullable = false)
    private int level;

    @Column(nullable = false)
    private double x;

    @Column(nullable = false)
    private double y;

    @Column(nullable = false)
    private double width;

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
}
