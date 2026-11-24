package com.picturecreate.doccanvas.service.ai;

import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 直接调用 OpenAI 兼容 embedding 接口。
 * 不依赖 Spring AI embedding starter，用 RestClient 原生调用。
 * 输出维度由 app.ai.chat.embedding.dimensions 配置（text-embedding-v3 为 1024）。
 * 密钥回退策略：BAILIAN_API_TOKEN > GPT_API_KEY > OPENAI_API_KEY > 空。
 */
@Profile("!lite")
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    /** embedding 单批最大条数 */
    private static final int MAX_BATCH = 10;

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final int dimensions;

    public EmbeddingClient(RestClient.Builder builder,
            org.springframework.core.env.Environment env) {
        String key = env.getProperty("app.ai.embedding.api-key");
        if (key == null || key.isBlank() || key.contains("placeholder")) {
            key = env.getProperty("BAILIAN_API_TOKEN");
        }
        if (key == null || key.isBlank()) {
            key = env.getProperty("GPT_API_KEY");
        }
        if (key == null || key.isBlank()) {
            key = env.getProperty("OPENAI_API_KEY");
        }
        this.apiKey = key;
        String baseUrl = env.getProperty("app.ai.embedding.base-url", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        this.model = env.getProperty("app.ai.embedding.model", "text-embedding-v3");
        this.dimensions = Integer.parseInt(env.getProperty("app.ai.embedding.dimensions", "1024"));
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + (key != null ? key : ""))
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String embed(String text) {
        List<String> vectors = embedAll(List.of(text));
        return vectors.isEmpty() ? null : vectors.get(0);
    }

    @SuppressWarnings("unchecked")
    public List<String> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty() || apiKey == null || apiKey.isBlank() || apiKey.contains("placeholder")) {
            log.warn("embedding 未配置有效密钥或输入为空，跳过向量化");
            return List.of();
        }
        List<String> allVectors = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += MAX_BATCH) {
            int end = Math.min(i + MAX_BATCH, texts.size());
            List<String> batch = texts.subList(i, end);
            try {
                Map<String, Object> body = Map.of(
                        "model", model,
                        "input", batch,
                        "dimensions", dimensions,
                        "encoding_format", "float");
                Map<String, Object> response = restClient.post()
                        .uri("/embeddings")
                        .body(body)
                        .retrieve()
                        .body(Map.class);
                if (response == null) {
                    log.warn("embedding 响应为空，跳过向量化");
                    return List.of();
                }
                List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
                if (data == null || data.isEmpty()) {
                    log.warn("embedding data 字段为空");
                    return List.of();
                }
                List<String> batchVectors = new ArrayList<>();
                for (Map<String, Object> item : data) {
                    try {
                        List<Number> embedding = (List<Number>) item.get("embedding");
                        if (embedding != null) {
                            batchVectors.add(formatVector(embedding));
                        }
                    } catch (Exception e) {
                        log.warn("解析单个 embedding 失败：{}", e.getMessage());
                    }
                }
                allVectors.addAll(batchVectors);
                log.debug("嵌入完成: batch {}-{}/{}", i + 1, end, texts.size());
            } catch (Exception exception) {
                log.warn("embedding 调用失败：{}", exception.getMessage());
                return List.of();
            }
        }
        return allVectors;
    }

    private String formatVector(List<Number> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values.get(i).doubleValue());
        }
        builder.append(']');
        return builder.toString();
    }
}