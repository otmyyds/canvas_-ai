package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.Card;

public interface CardRepository extends JpaRepository<Card, UUID> {

    List<Card> findByBoardIdOrderByCreatedAtAsc(UUID boardId);
}
