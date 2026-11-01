package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.AgentSession;

/**
 * Agent 会话数据访问层。
 * 会话按 owner + board 隔离，用户只能读取自己的会话。
 */
public interface AgentSessionRepository extends JpaRepository<AgentSession, UUID> {

    /** 用户在画布下的会话列表（最近活跃在前） */
    List<AgentSession> findByOwnerIdAndBoardIdOrderByLastActiveAtDesc(UUID ownerId, UUID boardId);

    /** 单个会话（校验 owner） */
    Optional<AgentSession> findByIdAndOwnerId(UUID id, UUID ownerId);
}
