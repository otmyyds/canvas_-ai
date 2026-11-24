package com.picturecreate.doccanvas.service;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.picturecreate.doccanvas.domain.AiTaskStatus;
import com.picturecreate.doccanvas.repository.AiTaskRepository;

/**
 * 画布编辑互斥：AI 计划执行/回退期间拒绝用户修改画布。
 *
 * AI 计划失败时按计划开始前的检查点重建 shapes/edges/assets/snapshot；
 * 如果用户在这期间改动画布，回退会把这些修改覆盖掉，甚至与恢复过程交错。
 * 因此服务端必须做最终校验，不能只依赖前端禁用按钮。
 */
@Service
public class BoardEditingGuard {

    private static final List<AiTaskStatus> BUSY_STATUSES = List.of(
            AiTaskStatus.RUNNING, AiTaskStatus.ABORTING);

    private final AiTaskRepository taskRepository;

    @Value("${app.features.ai-enabled:true}")
    private boolean aiEnabled;

    public BoardEditingGuard(AiTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public void assertEditable(UUID boardId) {
        if (!aiEnabled) {
            return;
        }
        if (taskRepository.countByBoardIdAndStatusIn(boardId, BUSY_STATUSES) > 0) {
            throw new IllegalStateException("AI 任务正在执行，画布暂不可编辑，请等待任务结束");
        }
    }
}
