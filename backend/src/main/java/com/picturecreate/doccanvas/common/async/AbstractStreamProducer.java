package com.picturecreate.doccanvas.common.async;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.common.constant.AiTaskStreamConstants;
import com.picturecreate.doccanvas.infrastructure.redis.RedisService;

/**
 * Redis Stream 生产者模板基类。
 * 统一消息发送骨架与失败处理逻辑。
 */
public abstract class AbstractStreamProducer<T> {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final RedisService redisService;

    protected AbstractStreamProducer(RedisService redisService) {
        this.redisService = redisService;
    }

    protected void sendTask(T payload) {
        try {
            String messageId = redisService.streamAdd(
                    streamKey(),
                    buildMessage(payload),
                    AiTaskStreamConstants.STREAM_MAX_LEN);
            log.info("{}任务已发送到 Stream：{}, messageId={}",
                    taskDisplayName(), payloadIdentifier(payload), messageId);
        } catch (Exception e) {
            log.error("发送{}任务失败：{}, error={}",
                    taskDisplayName(), payloadIdentifier(payload), e.getMessage(), e);
            onSendFailed(payload, "任务入队失败：" + e.getMessage());
        }
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract Map<String, String> buildMessage(T payload);

    protected abstract String payloadIdentifier(T payload);

    protected abstract void onSendFailed(T payload, String error);
}
