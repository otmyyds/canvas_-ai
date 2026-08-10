package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "boards")
public class Board extends BaseEntity {

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BoardVisibility visibility = BoardVisibility.PRIVATE;

    protected Board() {
    }

    public Board(UUID ownerId, String title) {
        this.ownerId = ownerId;
        this.title = title;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public BoardVisibility getVisibility() {
        return visibility;
    }

    public void rename(String title) {
        this.title = title;
    }

    public void setVisibility(BoardVisibility visibility) {
        this.visibility = visibility;
    }
}
