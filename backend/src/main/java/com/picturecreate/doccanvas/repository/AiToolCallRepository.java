package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.AiToolCall;

public interface AiToolCallRepository extends JpaRepository<AiToolCall, UUID> {

    List<AiToolCall> findByTaskIdOrderByCreatedAtAsc(UUID taskId);
}
