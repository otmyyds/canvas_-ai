package com.picturecreate.doccanvas.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Cors cors, Ai ai, RustFs rustfs) {

    public record Cors(List<String> allowedOrigins, List<String> allowedOriginPatterns) {
    }

    public record Ai(Task task, Llm llm, Image image, WebSearch webSearch, Agent agent, Quota quota) {
    }

    /** 模型网关配置：默认模型、降级模型、按操作覆盖模型、重试策略 */
    public record Llm(String defaultModel, String fallbackModel, Map<String, String> operations, LlmRetry retry) {
    }

    public record LlmRetry(int maxAttempts, long initialBackoffMs, double multiplier, long maxBackoffMs) {
    }

    public record Task(
            int perUserConcurrency,
            int perBoardWriteConcurrency,
            int consumerConcurrency) {
    }

    /** Agent 独立预算，不复用旧生成管线的 3 次模型调用上限 */
    public record Agent(
            int maxSteps,
            int maxModelCalls,
            int maxToolCalls,
            long maxTotalTokens,
            int maxDurationSeconds,
            int maxBranches,
            int branchParallelism,
            int branchMaxModelCalls,
            int branchMaxToolCalls,
            int branchMaxTokens,
            int branchMaxChildren,
            int preflightMaxModelCalls,
            int preflightMaxToolCalls,
            int preflightMaxDurationSeconds,
            int sessionWindowMessages,
            int sessionSummaryThreshold,
            boolean criticEnabled,
            int criticMinNodes,
            int criticMaxRounds,
            int maxNodesPerTask) {
    }

    /** 每用户配额 */
    public record Quota(long dailyTokensPerUser) {
    }

    public record Image(String apiKey, String baseUrl, String model) {
    }

    public record WebSearch(String apiKey, String baseUrl) {
    }

    public record RustFs(String endpoint, String accessKey, String secretKey, String bucket, String region) {
    }
}
