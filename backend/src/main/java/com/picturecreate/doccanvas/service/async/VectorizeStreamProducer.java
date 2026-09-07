package com.picturecreate.doccanvas.service.async;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.common.async.AbstractStreamProducer;
import com.picturecreate.doccanvas.common.constant.AiTaskStreamConstants;
import com.picturecreate.doccanvas.infrastructure.redis.RedisService;

/**
 * 向量化生产者：把资料文件 id 发到 vectorize:stream，由消费者触发向量化。
 */
@Component
public class VectorizeStreamProducer extends AbstractStreamProducer<UUID> {

    public VectorizeStreamProducer(RedisService redisService) {
        super(redisService);
    }

    /** 发送向量化任务到 Stream */
    public void send(UUID sourceFileId) {
        sendTask(sourceFileId);
    }

    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    @Override
    protected String streamKey() {
        return AiTaskStreamConstants.VECTORIZE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(UUID sourceFileId) {
        return Map.of(
                AiTaskStreamConstants.FIELD_SOURCE_FILE_ID, sourceFileId.toString(),
                AiTaskStreamConstants.FIELD_RETRY_COUNT, "0");
    }

    @Override
    protected String payloadIdentifier(UUID sourceFileId) {
        return "sourceFileId=" + sourceFileId;
    }

    @Override
    protected void onSendFailed(UUID sourceFileId, String error) {
        log.error("向量化任务入队失败，该文件将缺少检索片段：sourceFileId={}, error={}", sourceFileId, error);
    }
}
