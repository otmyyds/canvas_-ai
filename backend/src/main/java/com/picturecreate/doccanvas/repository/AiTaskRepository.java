package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.AiTask;
import com.picturecreate.doccanvas.domain.AiTaskStatus;

public interface AiTaskRepository extends JpaRepository<AiTask, UUID> {

    List<AiTask> findByBoardIdAndOwnerIdOrderByCreatedAtDesc(UUID boardId, UUID ownerId);

    Optional<AiTask> findByIdAndOwnerId(UUID id, UUID ownerId);

    long countByOwnerIdAndStatusIn(UUID ownerId, List<AiTaskStatus> statuses);

    long countByBoardIdAndStatusIn(UUID boardId, List<AiTaskStatus> statuses);
}
