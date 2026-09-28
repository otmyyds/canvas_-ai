package com.picturecreate.doccanvas.infrastructure.redis;

import java.time.Duration;
import java.util.Map;

import org.redisson.api.RBucket;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Redis 服务封装（基于 Redisson）。
 * 提供 AI 任务取消标记（字符串键值）与 Stream 消息队列两类能力。
 * 按需精简：仅保留当前项目实际用到的操作，分布式锁/哈希/列表等后续需要时再补。
 */
@Service
public class RedisService {

    private static final Logger log = LoggerFactory.getLogger(RedisService.class);

    private final RedissonClient redissonClient;

    public RedisService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    // ==================== 字符串键值操作（StringCodec） ====================

    /** 设置字符串值（带过期时间） */
    public void setString(String key, String value, Duration ttl) {
        RBucket<String> bucket = redissonClient.getBucket(key, StringCodec.INSTANCE);
        bucket.set(value, ttl);
    }

    /** 获取字符串值（不存在返回 null） */
    public String getString(String key) {
        RBucket<String> bucket = redissonClient.getBucket(key, StringCodec.INSTANCE);
        return bucket.get();
    }

    // ==================== Stream 消息队列 ====================

    /** Stream 消息处理器接口 */
    @FunctionalInterface
    public interface StreamMessageProcessor {
        void process(StreamMessageId messageId, Map<String, String> data);
    }

    /**
     * 消费 Stream 消息（消费者组阻塞模式）。
     * 使用 Redis BLOCK 参数让服务端等待消息，比客户端轮询更高效。
     *
     * @return true 处理了消息，false 超时无消息
     */
    public boolean streamConsumeMessages(
            String streamKey,
            String groupName,
            String consumerName,
            int count,
            long blockTimeoutMs,
            StreamMessageProcessor processor) {

        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        Map<StreamMessageId, Map<String, String>> messages = stream.readGroup(
                groupName,
                consumerName,
                StreamReadGroupArgs.neverDelivered()
                        .count(count)
                        .timeout(Duration.ofMillis(blockTimeoutMs)));

        if (messages == null || messages.isEmpty()) {
            return false;
        }

        for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
            processor.process(entry.getKey(), entry.getValue());
        }
        return true;
    }

    /** 创建消费者组（组已存在则忽略） */
    public void createStreamGroup(String streamKey, String groupName) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        try {
            stream.createGroup(StreamCreateGroupArgs.name(groupName).makeStream());
            log.info("创建 Stream 消费者组：stream={}, group={}", streamKey, groupName);
        } catch (Exception e) {
            if (!e.getMessage().contains("BUSYGROUP")) {
                log.warn("创建消费者组失败：{}", e.getMessage());
            }
        }
    }

    /**
     * 发送消息到 Stream（带长度限制，超限自动裁剪旧消息）。
     *
     * @return 消息 ID
     */
    public String streamAdd(String streamKey, Map<String, String> message, int maxLen) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        StreamAddArgs<String, String> args = StreamAddArgs.entries(message);
        if (maxLen > 0) {
            args.trimNonStrict().maxLen(maxLen);
        }
        StreamMessageId messageId = stream.add(args);
        log.debug("发送 Stream 消息：stream={}, messageId={}, maxLen={}", streamKey, messageId, maxLen);
        return messageId.toString();
    }

    /** 确认消息已处理 */
    public void streamAck(String streamKey, String groupName, StreamMessageId... ids) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        stream.ack(groupName, ids);
    }
}
