package com.picturecreate.doccanvas.service.async;

import java.util.Map;
import java.util.UUID;

import org.redisson.api.StreamMessageId;
import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.common.async.AbstractStreamConsumer;
import com.picturecreate.doccanvas.common.constant.AiTaskStreamConstants;
import com.picturecreate.doccanvas.domain.SourceFile;
import com.picturecreate.doccanvas.infrastructure.redis.RedisService;
import com.picturecreate.doccanvas.repository.SourceFileRepository;
import com.picturecreate.doccanvas.service.DocumentChunkService;

/**
 * 向量化消费者：从 vectorize:stream 消费消息，重新加载资料文件的解析文本并触发分块 + 向量化。
 * 文件内容从数据库读取，Stream 只传 id，避免把大段文本塞进消息。
 */
@Component
public class VectorizeStreamConsumer extends AbstractStreamConsumer<UUID> {

    private final DocumentChunkService chunkService;
    private final SourceFileRepository sourceFileRepository;

    public VectorizeStreamConsumer(RedisService redisService, DocumentChunkService chunkService,
            SourceFileRepository sourceFileRepository) {
        super(redisService);
        this.chunkService = chunkService;
        this.sourceFileRepository = sourceFileRepository;
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
    protected String groupName() {
        return AiTaskStreamConstants.VECTORIZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AiTaskStreamConstants.VECTORIZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "vectorize-consumer";
    }

    @Override
    protected UUID parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String sourceFileId = data.get(AiTaskStreamConstants.FIELD_SOURCE_FILE_ID);
        if (sourceFileId == null) {
            log.warn("消息格式错误，跳过：messageId={}", messageId);
            return null;
        }
        try {
            return UUID.fromString(sourceFileId);
        } catch (IllegalArgumentException e) {
            log.warn("非法 sourceFileId，跳过：messageId={}, sourceFileId={}", messageId, sourceFileId);
            return null;
        }
    }

    @Override
    protected String payloadIdentifier(UUID sourceFileId) {
        return "sourceFileId=" + sourceFileId;
    }

    @Override
    protected void markProcessing(UUID sourceFileId) {
        // 向量化无独立状态字段，parse_status 在解析阶段已置 READY
    }

    @Override
    protected void processBusiness(UUID sourceFileId) {
        SourceFile sourceFile = sourceFileRepository.findById(sourceFileId).orElse(null);
        if (sourceFile == null || sourceFile.getTextExcerpt() == null || sourceFile.getTextExcerpt().isBlank()) {
            log.info("文件已删除或无解析文本，跳过向量化：sourceFileId={}", sourceFileId);
            return;
        }
        chunkService.vectorize(sourceFileId, sourceFile.getBoardId(), sourceFile.getOwnerId(),
                sourceFile.getTextExcerpt());
    }

    @Override
    protected void markCompleted(UUID sourceFileId) {
        // 向量化无独立状态字段
    }

    @Override
    protected void markFailed(UUID sourceFileId, String error) {
        log.error("向量化失败（已重试）：sourceFileId={}, error={}", sourceFileId, error);
    }

    @Override
    protected void retryMessage(UUID sourceFileId, int retryCount) {
        Map<String, String> message = Map.of(
                AiTaskStreamConstants.FIELD_SOURCE_FILE_ID, sourceFileId.toString(),
                AiTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount));
        redisService().streamAdd(AiTaskStreamConstants.VECTORIZE_STREAM_KEY, message,
                AiTaskStreamConstants.STREAM_MAX_LEN);
    }
}
