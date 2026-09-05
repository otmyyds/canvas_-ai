package com.picturecreate.doccanvas.service.ai;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.picturecreate.doccanvas.config.AppProperties;

/**
 * 联网搜索服务：调用 SerpAPI 的 search.json 接口，返回精简的搜索结果文本。
 * 供 AI 工具 web_search 使用，回答版本、日期、近期事件等时效性问题。
 * 接口异常不向上抛，统一返回可读的失败信息，避免中断模型调用。
 */
@Component
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    private final RestClient restClient;
    private final String apiKey;

    public WebSearchService(RestClient.Builder builder, AppProperties properties) {
        this.apiKey = properties.ai().webSearch().apiKey();
        this.restClient = builder.baseUrl(properties.ai().webSearch().baseUrl()).build();
    }

    /**
     * 联网搜索并格式化为文本。
     * 优先返回 answer_box 直接答案，再附 organic_results 的标题、链接与摘要。
     */
    @SuppressWarnings("unchecked")
    public String search(String query) {
        if (apiKey == null || apiKey.isBlank()) {
            return "未配置 SERPAPI_API_KEY，无法联网搜索";
        }
        try {
            Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search.json")
                            .queryParam("q", query)
                            .queryParam("api_key", apiKey)
                            .queryParam("num", 6)
                            .queryParam("hl", "zh-CN")
                            .build())
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                return "联网搜索无结果";
            }
            if (response.get("error") != null) {
                log.warn("SerpAPI 返回错误：{}", response.get("error"));
                return "联网搜索失败：" + response.get("error");
            }
            StringBuilder result = new StringBuilder();
            appendAnswerBox(response.get("answer_box"), result);
            List<Map<String, Object>> results = organicResults(response.get("organic_results"));
            if (results.isEmpty()) {
                return result.isEmpty() ? "联网搜索无结果" : result.toString();
            }
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append("搜索结果：\n");
            int index = 1;
            for (Map<String, Object> item : results) {
                Object title = item.get("title");
                Object link = item.get("link");
                Object snippet = item.get("snippet");
                if (title == null && snippet == null) {
                    continue;
                }
                result.append(index++).append(". ").append(title == null ? "" : title).append('\n');
                if (link != null) {
                    result.append("   ").append(link).append('\n');
                }
                if (snippet != null) {
                    result.append("   ").append(snippet).append('\n');
                }
            }
            return result.toString();
        } catch (Exception exception) {
            log.warn("联网搜索失败：{}", exception.getMessage());
            return "联网搜索失败：" + exception.getMessage();
        }
    }

    /** 追加 answer_box 直接答案（如「最新版本为 X」），无则跳过 */
    private void appendAnswerBox(Object answerBox, StringBuilder result) {
        if (!(answerBox instanceof Map<?, ?> box)) {
            return;
        }
        Object answer = box.get("answer");
        Object snippet = box.get("snippet");
        Object title = box.get("title");
        if (answer != null) {
            result.append("直接答案：").append(answer).append('\n');
        } else if (snippet != null) {
            result.append("直接答案").append(title != null ? "（" + title + "）" : "").append("：").append(snippet).append('\n');
        }
    }

    /** 提取 organic_results 为 Map 列表 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> organicResults(Object organic) {
        if (!(organic instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?>) {
                results.add((Map<String, Object>) item);
            }
        }
        return results;
    }
}
