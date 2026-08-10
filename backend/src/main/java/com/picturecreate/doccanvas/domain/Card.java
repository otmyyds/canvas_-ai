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

@Entity
@Table(name = "cards")
public class Card extends BaseEntity {

    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    @Column(nullable = false)
    private String title;

    @Column(name = "content_markdown", nullable = false, columnDefinition = "text")
    private String contentMarkdown;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_type", nullable = false)
    private CardType cardType;

    @Column(nullable = false)
    private double x;

    @Column(nullable = false)
    private double y;

    @Column(nullable = false)
    private double width;

    @Column(nullable = false)
    private double height;

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
