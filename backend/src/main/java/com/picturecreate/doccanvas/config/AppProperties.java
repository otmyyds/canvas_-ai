package com.picturecreate.doccanvas.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Cors cors, Ai ai, RustFs rustfs) {

    public record Cors(List<String> allowedOrigins, List<String> allowedOriginPatterns) {
    }

    public record Ai(String provider, Task task, Retry retry, Image image, WebSearch webSearch) {
    }

    public record Task(
            int maxSteps,
            int maxModelCalls,
            int maxToolCalls,
            int maxDurationSeconds,
            int perUserConcurrency,
            int perBoardWriteConcurrency) {
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
