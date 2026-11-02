package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.AgentMessage;

/**
 * Agent 会话消息数据访问层。
 * 查询按会话和 owner 双重过滤，避免通过会话 id 读取他人消息。
 */
public interface AgentMessageRepository extends JpaRepository<AgentMessage, UUID> {

    /** 会话下的全部消息（按时间升序） */
    List<AgentMessage> findBySessionIdAndOwnerIdOrderByCreatedAtAsc(UUID sessionId, UUID ownerId);

    /** 会话消息总数：判断是否触发滚动摘要 */
    long countBySessionId(UUID sessionId);
}
