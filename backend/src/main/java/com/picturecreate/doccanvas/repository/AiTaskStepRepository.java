package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.AiTaskStep;

public interface AiTaskStepRepository extends JpaRepository<AiTaskStep, UUID> {

    List<AiTaskStep> findByTaskIdOrderByCreatedAtAsc(UUID taskId);
}
