package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.BoardEdge;

public interface BoardEdgeRepository extends JpaRepository<BoardEdge, UUID> {

    List<BoardEdge> findByBoardIdOrderByCreatedAtAsc(UUID boardId);

    List<BoardEdge> findByFromCardIdOrToCardId(UUID fromCardId, UUID toCardId);
}
