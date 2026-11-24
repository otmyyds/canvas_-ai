package com.picturecreate.doccanvas.service.ai;

import org.springframework.context.annotation.Profile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturecreate.doccanvas.config.AppProperties;

/**
 * 文生图服务：调用百炼 DashScope 原生文生图接口（wanx/qwen-image）。
 * 流程：提交异步任务 → 轮询到 SUCCEEDED → 下载图片字节。
 * 与聊天用的 OpenAI 兼容接口不同，这里走 DashScope 原生 /api/v1/services/aigc/text2image。
 */
@Profile("!lite")
@Component
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    private static final String SUBMIT_PATH = "/api/v1/services/aigc/text2image/image-synthesis";
    private static final String TASKS_PATH = "/api/v1/tasks/";

    private final AppProperties.Image config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ImageGenerationService(AppProperties properties) {
        this.config = properties.ai().image();
    }

    /** 文生图：提交任务 → 轮询结果 → 下载图片字节 */
    public byte[] generate(String prompt) {
        String taskId = submit(prompt);
        String imageUrl = poll(taskId);
        return download(imageUrl);
    }

    @SuppressWarnings("unchecked")
    private String submit(String prompt) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", config.model(),
                    "input", Map.of("prompt", prompt),
                    "parameters", Map.of("size", "1024*1024", "n", 1)));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + SUBMIT_PATH))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .header("X-DashScope-Async", "enable")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("文生图提交失败：" + response.statusCode() + " " + response.body());
            }
            Map<String, Object> root = objectMapper.readValue(response.body(), Map.class);
            Map<String, Object> output = (Map<String, Object>) root.get("output");
            Object taskId = output == null ? null : output.get("task_id");
            if (taskId == null) {
                throw new IllegalStateException("文生图提交响应缺少 task_id");
            }
            log.info("文生图任务已提交：taskId={}", taskId);
            return taskId.toString();
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("调用文生图接口失败：" + exception.getMessage(), exception);
        }
    }

    @SuppressWarnings("unchecked")
    private String poll(String taskId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + TASKS_PATH + taskId))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline) {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                Map<String, Object> root = objectMapper.readValue(response.body(), Map.class);
                Map<String, Object> output = (Map<String, Object>) root.get("output");
                String status = output == null ? null : String.valueOf(output.get("task_status"));
                if ("SUCCEEDED".equals(status)) {
                    List<?> results = (List<?>) output.get("results");
                    if (results == null || results.isEmpty()) {
                        throw new IllegalStateException("文生图任务无结果");
                    }
                    Object url = ((Map<String, Object>) results.get(0)).get("url");
                    if (url == null) {
                        throw new IllegalStateException("文生图结果缺少 url");
                    }
                    log.info("文生图任务完成：taskId={}", taskId);
                    return url.toString();
                }
                if ("FAILED".equals(status) || "CANCELED".equals(status)) {
                    throw new IllegalStateException("文生图任务失败：" + status);
                }
                Thread.sleep(1500);
            }
            throw new IllegalStateException("文生图任务超时");
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("轮询文生图任务失败：" + exception.getMessage(), exception);
        }
    }

    private byte[] download(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("下载图片失败：" + response.statusCode());
            }
            return response.body();
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("下载图片失败：" + exception.getMessage(), exception);
        }
    }
}
