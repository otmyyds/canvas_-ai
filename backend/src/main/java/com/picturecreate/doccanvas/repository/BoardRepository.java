package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.Board;

public interface BoardRepository extends JpaRepository<Board, UUID> {

    List<Board> findByOwnerIdOrderByUpdatedAtDesc(UUID ownerId);

    Optional<Board> findByIdAndOwnerId(UUID id, UUID ownerId);
}
