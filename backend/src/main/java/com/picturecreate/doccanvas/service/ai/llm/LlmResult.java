package com.picturecreate.doccanvas.service.ai.llm;

/** 一次逻辑模型调用的结果与用量 */
public record LlmResult(
        String content,
        String model,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        long latencyMs,
        boolean degraded) {
}
