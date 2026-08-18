package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.Board;

/**
 * 画布数据访问层。按 owner 查画布列表；findByIdAndOwnerId 校验归属。
 */
public interface BoardRepository extends JpaRepository<Board, UUID> {

    List<Board> findByOwnerIdOrderByUpdatedAtDesc(UUID ownerId);

    Optional<Board> findByIdAndOwnerId(UUID id, UUID ownerId);
}
