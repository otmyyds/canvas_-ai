package com.picturecreate.doccanvas.service.async;

import java.util.Map;
import java.util.UUID;

import org.redisson.api.StreamMessageId;
import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.common.async.AbstractStreamConsumer;
import com.picturecreate.doccanvas.common.constant.AiTaskStreamConstants;
import com.picturecreate.doccanvas.infrastructure.redis.RedisService;
import com.picturecreate.doccanvas.service.AiTaskService;

/**
 * AI 任务消费者：从 ai-task:stream 消费消息并触发任务执行。
 * 只做「薄触发」——状态机（步骤/进度/成功/失败/中断）仍由 AiTaskService.runTask 管线管理，
 * 因此 markProcessing/markCompleted 为空实现。
 */
@Component
public class AiTaskStreamConsumer extends AbstractStreamConsumer<UUID> {

    private final AiTaskService aiTaskService;

    public AiTaskStreamConsumer(RedisService redisService, AiTaskService aiTaskService) {
        super(redisService);
        this.aiTaskService = aiTaskService;
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
    protected String groupName() {
        return AiTaskStreamConstants.AI_TASK_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AiTaskStreamConstants.AI_TASK_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "ai-task-consumer";
    }

    @Override
    protected UUID parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String taskId = data.get(AiTaskStreamConstants.FIELD_TASK_ID);
        if (taskId == null) {
            log.warn("消息格式错误，跳过：messageId={}", messageId);
            return null;
        }
        try {
            return UUID.fromString(taskId);
        } catch (IllegalArgumentException e) {
            log.warn("非法 taskId，跳过：messageId={}, taskId={}", messageId, taskId);
            return null;
        }
    }

    @Override
    protected String payloadIdentifier(UUID taskId) {
        return "taskId=" + taskId;
    }

    @Override
    protected void markProcessing(UUID taskId) {
        // runTask 内部会标记 RUNNING，这里无需处理
    }

    @Override
    protected void processBusiness(UUID taskId) {
        aiTaskService.executeTask(taskId);
    }

    @Override
    protected void markCompleted(UUID taskId) {
        // runTask 内部会标记 SUCCEEDED，这里无需处理
    }

    @Override
    protected void markFailed(UUID taskId, String error) {
        log.error("AI 任务处理异常（runTask 未兜住）：taskId={}, error={}", taskId, error);
    }

    @Override
    protected void retryMessage(UUID taskId, int retryCount) {
        // 兜底：runTask 意外崩溃（未捕获异常）后重新投递
        Map<String, String> message = Map.of(
                AiTaskStreamConstants.FIELD_TASK_ID, taskId.toString(),
                AiTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount));
        redisService().streamAdd(AiTaskStreamConstants.AI_TASK_STREAM_KEY, message,
                AiTaskStreamConstants.STREAM_MAX_LEN);
    }
}
