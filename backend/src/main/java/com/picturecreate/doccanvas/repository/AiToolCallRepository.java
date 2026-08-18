package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.AiToolCall;

/**
 * AI 工具调用审计数据访问层。按任务查工具调用记录（排查/审计用）。
 */
public interface AiToolCallRepository extends JpaRepository<AiToolCall, UUID> {

    List<AiToolCall> findByTaskIdOrderByCreatedAtAsc(UUID taskId);
}
