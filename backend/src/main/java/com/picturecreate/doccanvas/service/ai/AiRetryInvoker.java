package com.picturecreate.doccanvas.service.ai;

import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.config.AppProperties;

/**
 * 统一重试调用器：封装「生成 → 解析 → 校验」的重试循环。
 * attempt 正常返回即成功；抛 AttemptFailedException 表示可重试（消息会作为反馈传给下一次）。
 * 其他异常（如网络错误）直接向上传播，不重试。maxAttemptsOverride 为空或 <=0 时用全局默认上限。
 */
@Component
public class AiRetryInvoker {

    private final int defaultMaxAttempts;

    public AiRetryInvoker(AppProperties properties) {
        this.defaultMaxAttempts = Math.max(1, properties.ai().retry().maxAttempts());
    }

    public <T> T invoke(Logger log, String context, Integer maxAttemptsOverride, Attempt<T> attempt) {
        int max = (maxAttemptsOverride != null && maxAttemptsOverride > 0) ? maxAttemptsOverride : defaultMaxAttempts;
        String lastError = null;
        for (int attemptNo = 1; attemptNo <= max; attemptNo++) {
            try {
                return attempt.run(lastError);
            } catch (AttemptFailedException failure) {
                lastError = failure.getMessage();
                log.warn("{}第 {} 次尝试失败：{}", context, attemptNo, lastError);
            }
        }
        throw new IllegalStateException(context + "失败：" + (lastError == null ? "未知原因" : lastError));
    }

    /** 单次尝试：previousError 为上一次失败原因（首次为 null） */
    @FunctionalInterface
    public interface Attempt<T> {
        T run(String previousError) throws AttemptFailedException;
    }

    /** 可重试失败（解析/校验未通过），消息是反馈给模型重试的原因 */
    public static class AttemptFailedException extends RuntimeException {
        public AttemptFailedException(String message) {
            super(message);
        }
    }
}
