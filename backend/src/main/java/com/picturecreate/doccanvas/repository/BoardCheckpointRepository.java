package com.picturecreate.doccanvas.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.BoardCheckpoint;

/**
 * 画布检查点数据访问层。每个计划任务最多一行。
 */
public interface BoardCheckpointRepository extends JpaRepository<BoardCheckpoint, UUID> {

    Optional<BoardCheckpoint> findByTaskId(UUID taskId);

    void deleteByTaskId(UUID taskId);
}
