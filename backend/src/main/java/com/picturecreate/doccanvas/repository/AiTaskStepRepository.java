package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.AiTaskStep;

/**
 * 任务步骤数据访问层。按任务查步骤，按计划顺序升序。
 */
public interface AiTaskStepRepository extends JpaRepository<AiTaskStep, UUID> {

    List<AiTaskStep> findByTaskIdOrderBySeqAsc(UUID taskId);
}
