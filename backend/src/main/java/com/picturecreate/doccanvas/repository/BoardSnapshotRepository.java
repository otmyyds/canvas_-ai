package com.picturecreate.doccanvas.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.BoardSnapshot;

public interface BoardSnapshotRepository extends JpaRepository<BoardSnapshot, UUID> {

    Optional<BoardSnapshot> findFirstByBoardIdOrderByVersionDesc(UUID boardId);

    long countByBoardId(UUID boardId);
}
