package com.picturecreate.doccanvas.service.async;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.common.async.AbstractStreamProducer;
import com.picturecreate.doccanvas.common.constant.AiTaskStreamConstants;
import com.picturecreate.doccanvas.infrastructure.redis.RedisService;
import com.picturecreate.doccanvas.repository.AiTaskRepository;

/**
 * AI 任务生产者：把任务 id 发到 ai-task:stream，由消费者触发执行。
 */
@Component
public class AiTaskStreamProducer extends AbstractStreamProducer<UUID> {

    private final AiTaskRepository taskRepository;

    public AiTaskStreamProducer(RedisService redisService, AiTaskRepository taskRepository) {
        super(redisService);
        this.taskRepository = taskRepository;
    }

    /** 发送 AI 任务到 Stream */
    public void send(UUID taskId) {
        sendTask(taskId);
    }

    @Override
    protected String taskDisplayName() {
        return "AI 任务";
    }

    @Override
    protected String streamKey() {
        return AiTaskStreamConstants.AI_TASK_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(UUID taskId) {
        return Map.of(
                AiTaskStreamConstants.FIELD_TASK_ID, taskId.toString(),
                AiTaskStreamConstants.FIELD_RETRY_COUNT, "0");
    }

    @Override
    protected String payloadIdentifier(UUID taskId) {
        return "taskId=" + taskId;
    }

    @Override
    protected void onSendFailed(UUID taskId, String error) {
        // 入队失败则任务永远无法执行，立即落 FAILED，避免卡在 QUEUED
        taskRepository.findById(taskId).ifPresent(task -> {
            task.markFailed("任务入队失败：" + truncateError(error));
            taskRepository.save(task);
        });
    }
}
