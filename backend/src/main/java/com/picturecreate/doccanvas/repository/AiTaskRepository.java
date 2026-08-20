package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.AiTask;
import com.picturecreate.doccanvas.domain.AiTaskStatus;

/**
 * AI 任务数据访问层。
 * 查询按画布+owner 隔离；findByIdAndOwnerId 保证只能查自己的任务。
 */
public interface AiTaskRepository extends JpaRepository<AiTask, UUID> {

    /** 画布下所有任务（按创建时间倒序，最新的在最前） */
    List<AiTask> findByBoardIdAndOwnerIdOrderByCreatedAtDesc(UUID boardId, UUID ownerId);

    /** 单个任务（校验 owner，他人任务查不到） */
    Optional<AiTask> findByIdAndOwnerId(UUID id, UUID ownerId);

    /** 该用户当前活跃任务数（并发限制用：每用户最多 N 个） */
    long countByOwnerIdAndStatusIn(UUID ownerId, List<AiTaskStatus> statuses);

    /** 该画布当前活跃任务数（并发限制用：每画布最多 M 个写入任务） */
    long countByBoardIdAndStatusIn(UUID boardId, List<AiTaskStatus> statuses);

    /** 按状态查询任务（定时回收僵尸 ABORTING 任务用） */
    List<AiTask> findByStatus(AiTaskStatus status);

    /** 按状态集合查询任务（启动时回收遗留活跃任务用） */
    List<AiTask> findByStatusIn(List<AiTaskStatus> statuses);
}
