package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.AgentRun;
import com.picturecreate.doccanvas.domain.AgentRunStatus;

/**
 * Agent 运行记录数据访问层。
 * 所有按用户查询的方法都带 ownerId，避免跨用户读取运行轨迹。
 */
public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {

    /** 任务下的运行记录（按创建时间升序，便于还原执行顺序） */
    List<AgentRun> findByTaskIdOrderByCreatedAtAsc(UUID taskId);

    /** 单个运行记录（校验 owner） */
    Optional<AgentRun> findByIdAndOwnerId(UUID id, UUID ownerId);

    /** 任务下指定状态的运行（回收僵尸任务时关闭未收尾的运行） */
    List<AgentRun> findByTaskIdAndStatus(UUID taskId, AgentRunStatus status);

    /** 任务下的运行数量（成本归属和测试核对用） */
    long countByTaskId(UUID taskId);
}
