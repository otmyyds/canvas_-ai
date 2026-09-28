package com.picturecreate.doccanvas.common.constant;

/**
 * 异步任务 Redis Stream 常量。
 * 包含 AI 任务与文件向量化两条流的配置。
 */
public final class AiTaskStreamConstants {

    private AiTaskStreamConstants() {
        // 私有构造函数，防止实例化
    }

    // ========== 通用消息字段 ==========

    /** 重试次数字段 */
    public static final String FIELD_RETRY_COUNT = "retryCount";

    /** 任务 ID 字段 */
    public static final String FIELD_TASK_ID = "taskId";

    /** 资料文件 ID 字段 */
    public static final String FIELD_SOURCE_FILE_ID = "sourceFileId";

    // ========== 通用消费者配置 ==========

    /** 最大重试次数 */
    public static final int MAX_RETRY_COUNT = 3;

    /** 每次拉取的消息批次大小 */
    public static final int BATCH_SIZE = 10;

    /** 消费者轮询间隔（毫秒） */
    public static final long POLL_INTERVAL_MS = 1000;

    /** Stream 最大长度（自动裁剪旧消息，防止无限增长） */
    public static final int STREAM_MAX_LEN = 1000;

    // ========== AI 任务 Stream 配置 ==========

    /** AI 任务 Stream Key */
    public static final String AI_TASK_STREAM_KEY = "ai-task:stream";

    /** AI 任务 Consumer Group 名称 */
    public static final String AI_TASK_GROUP_NAME = "ai-task-group";

    /** AI 任务 Consumer 名称前缀 */
    public static final String AI_TASK_CONSUMER_PREFIX = "ai-task-consumer-";

    // ========== 文件向量化 Stream 配置 ==========

    /** 向量化 Stream Key */
    public static final String VECTORIZE_STREAM_KEY = "vectorize:stream";

    /** 向量化 Consumer Group 名称 */
    public static final String VECTORIZE_GROUP_NAME = "vectorize-group";

    /** 向量化 Consumer 名称前缀 */
    public static final String VECTORIZE_CONSUMER_PREFIX = "vectorize-consumer-";
}
