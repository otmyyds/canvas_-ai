package com.picturecreate.doccanvas.service.ai;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturecreate.doccanvas.domain.AiTaskType;

/**
 * 意图分类器（规划器）。
 *
 * 一次 LLM 调用把用户输入拆解为「生成类任务计划」或「闲聊/问答」，
 * 替代前端的关键词匹配表（matchTaskType），把路由决策收敛到后端。
 *
 * 输出结构化 JSON { "plan": ["类型1", "类型2", ...] }，plan 为按执行顺序排列的
 * 任务类型列表；单个任务就一个元素；纯聊天输出 ["CHAT"]。
 * 用 .content() 取纯文本 + 手动解析 JSON（百炼兼容模式不支持 Spring AI 结构化输出/tool calling）。
 * 解析失败由 AiRetryInvoker 统一重试。
 */
@Component
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PromptCatalog promptCatalog;
    private final AiRetryInvoker retryInvoker;

    public IntentClassifier(ChatClient.Builder builder, PromptCatalog promptCatalog, AiRetryInvoker retryInvoker) {
        this.chatClient = builder.build();
        this.promptCatalog = promptCatalog;
        this.retryInvoker = retryInvoker;
    }

    /**
     * 分类用户输入，拆解为有序的任务计划。
     *
     * @param prompt 用户输入
     * @param hasSelection 当前是否有选中的画布元素（用于区分「编辑选中元素」与「清空画布」）
     * @return 分类决策（plan 为空表示纯聊天）
     */
    public IntentDecision classify(String prompt, boolean hasSelection) {
        return retryInvoker.invoke(log, "意图分类", null, previousError -> {
            StringBuilder user = new StringBuilder("用户输入：").append(prompt)
                    .append("\n是否有选中元素：").append(hasSelection ? "是" : "否");
            if (previousError != null) {
                user.append("\n注意：上次输出未通过校验，原因：").append(previousError).append("。请只输出合法 JSON。");
            }
            String raw = chatClient.prompt()
                    .system(promptCatalog.classificationSystemPrompt())
                    .user(user.toString())
                    .call()
                    .content();
            log.info("意图分类原始输出: {}", raw);
            return parse(raw);
        });
    }

    /** 解析模型输出的 plan JSON，非法输出抛 AttemptFailedException（由 AiRetryInvoker 重试） */
    @SuppressWarnings("unchecked")
    private IntentDecision parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AiRetryInvoker.AttemptFailedException("意图分类未返回内容");
        }
        String json = stripCodeFence(raw);
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(json, Map.class);
        } catch (Exception exception) {
            throw new AiRetryInvoker.AttemptFailedException("意图分类输出不是合法 JSON：" + exception.getMessage());
        }
        Object planValue = root.get("plan");
        if (!(planValue instanceof List<?> list) || list.isEmpty()) {
            throw new AiRetryInvoker.AttemptFailedException("意图分类缺少 plan 字段");
        }
        List<String> values = list.stream()
                .map(String::valueOf)
                .map(String::trim)
                .map(String::toUpperCase)
                .toList();
        if (values.size() == 1 && "CHAT".equals(values.get(0))) {
            return IntentDecision.chat();
        }
        List<AiTaskType> plan = values.stream().map(value -> {
            try {
                return AiTaskType.valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw new AiRetryInvoker.AttemptFailedException("未知意图类型：" + value);
            }
        }).toList();
        return IntentDecision.task(plan);
    }

    /** 剥离 ```json ... ``` 代码围栏 */
    private String stripCodeFence(String raw) {
        String json = raw.trim();
        if (json.startsWith("```")) {
            int end = json.lastIndexOf("```");
            if (end > 3) {
                json = json.substring(3, end).trim();
                if (json.startsWith("json")) {
                    json = json.substring(4).trim();
                }
            }
        }
        return json;
    }

    /** 分类决策：plan 为按执行顺序排列的任务类型列表，空列表表示纯聊天 */
    public record IntentDecision(List<AiTaskType> plan) {

        public boolean isChat() {
            return plan.isEmpty();
        }

        public static IntentDecision chat() {
            return new IntentDecision(List.of());
        }

        public static IntentDecision task(List<AiTaskType> plan) {
            return new IntentDecision(plan);
        }
    }
}
