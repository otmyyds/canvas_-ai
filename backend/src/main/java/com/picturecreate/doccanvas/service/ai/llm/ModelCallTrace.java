package com.picturecreate.doccanvas.service.ai.llm;

/**
 * 一次实际模型请求的完整轨迹。
 * 每次 HTTP 尝试都会产生一条，包含重试和降级切换，用于还原数据库里的真实调用过程。
 */
public record ModelCallTrace(
        LlmOperation operation,
        String model,
        boolean degraded,
        String tools,
        String system,
        String user,
        String content,
        String finishReason,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String errorMessage) {
}
