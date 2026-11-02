package com.picturecreate.doccanvas.service;

import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.domain.AiTask;
import com.picturecreate.doccanvas.infrastructure.redis.RedisService;
import com.picturecreate.doccanvas.repository.AiTaskRepository;
import com.picturecreate.doccanvas.service.ai.llm.TaskAbortedException;

/**
 * AI 任务取消检查：DB abort_requested 与 Redis cancel key 双重标记。
 * 任务服务（中断接口）与计划执行器共用，避免执行器反向依赖任务服务。
 */
@Component
public class AiTaskCancellation {

    private final AiTaskRepository taskRepository;
    private final RedisService redisService;

    public AiTaskCancellation(AiTaskRepository taskRepository, RedisService redisService) {
        this.taskRepository = taskRepository;
        this.redisService = redisService;
    }

    /** 中断接口写入取消标记；宁可多设不可漏设，残留标记只会让任务提前停下 */
    public void markCancelled(UUID taskId) {
        redisService.setString(cancelKey(taskId), "1", Duration.ofHours(1));
    }

    /** 命中任一标记即抛 TaskAbortedException，由执行器统一收尾 */
    public void assertNotCancelled(UUID taskId) {
        String redisFlag = redisService.getString(cancelKey(taskId));
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        if (task.isAbortRequested() || "1".equals(redisFlag)) {
            throw new TaskAbortedException();
        }
    }

    private String cancelKey(UUID taskId) {
        return "ai-task:cancel:%s".formatted(taskId);
    }
}
