package com.picturecreate.doccanvas.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/**
 * 知识卡片（card）实体，对应 cards 表。
 * 卡片是画布核心内容单元：有标题 + Markdown 正文 + 类型 + 位置尺寸。
 * 由用户手动创建或 AI 任务生成（CreateCard 动作）。
 */
@Entity
@Table(name = "cards")
public class Card extends BaseEntity {

    /** 所属画布 id */
    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    /** 卡片标题 */
    @Column(nullable = false)
    private String title;

    /** Markdown 正文（渲染成卡片内容，支持标题/加粗/列表等 Markdown 语法） */
    @Column(name = "content_markdown", nullable = false, columnDefinition = "text")
    private String contentMarkdown;

    /** 卡片类型：NOTE/SUMMARY/IDEA/SOURCE/QUESTION/IMAGE/PROMPT */
    @Enumerated(EnumType.STRING)
    @Column(name = "card_type", nullable = false)
    private CardType cardType;

    /** 画布 x 坐标（tldraw 页面坐标） */
    @Column(nullable = false)
    private double x;

    /** 画布 y 坐标 */
    @Column(nullable = false)
    private double y;

    /** 卡片宽度 */
    @Column(nullable = false)
    private double width;

    /** 卡片高度 */
    @Column(nullable = false)
    private double height;

    /** 关联的资料文件 id（card_source_files 关联表，@ElementCollection 子表） */
    @ElementCollection
    @CollectionTable(name = "card_source_files", joinColumns = @JoinColumn(name = "card_id"))
    @Column(name = "source_file_id")
    private List<UUID> sourceFileIds = new ArrayList<>();

    protected Card() {
    }

    public Card(UUID boardId, String title, String contentMarkdown, CardType cardType, double x, double y,
            double width, double height) {
        this.boardId = boardId;
        this.title = title;
        this.contentMarkdown = contentMarkdown;
        this.cardType = cardType;
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

    public String getContentMarkdown() {
        return contentMarkdown;
    }

    public CardType getCardType() {
        return cardType;
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

    public List<UUID> getSourceFileIds() {
        return sourceFileIds;
    }

    public void update(String title, String contentMarkdown, CardType cardType, Double x, Double y, Double width,
            Double height) {
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
        if (contentMarkdown != null) {
            this.contentMarkdown = contentMarkdown;
        }
        if (cardType != null) {
            this.cardType = cardType;
        }
        if (x != null) {
            this.x = x;
        }
        if (y != null) {
            this.y = y;
        }
        if (width != null) {
            this.width = width;
        }
        if (height != null) {
            this.height = height;
        }
    }
}
