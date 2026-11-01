package com.picturecreate.doccanvas.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Cors cors, Ai ai, RustFs rustfs) {

    public record Cors(List<String> allowedOrigins, List<String> allowedOriginPatterns) {
    }

    public record Ai(String provider, Task task, Retry retry, Image image, WebSearch webSearch, Agent agent,
            Quota quota) {
    }

    public record Task(
            int maxSteps,
            int maxModelCalls,
            int maxToolCalls,
            int maxDurationSeconds,
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

    public record Retry(int maxAttempts) {
    }

    public record Image(String apiKey, String baseUrl, String model) {
    }

    public record WebSearch(String apiKey, String baseUrl) {
    }

    public record RustFs(String endpoint, String accessKey, String secretKey, String bucket, String region) {
    }
}
