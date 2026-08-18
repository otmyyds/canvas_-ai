package com.picturecreate.doccanvas.service.ai;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturecreate.doccanvas.domain.AiTaskType;

/**
 * 意图分类器。
 *
 * 一次 LLM 调用判断用户输入属于「生成类任务」还是「闲聊/问答」，
 * 替代前端的关键词匹配表（matchTaskType），把路由决策收敛到后端。
 *
 * 输出结构化 JSON { "intent": "..." }，intent 取值限定为已实现的 7 个任务类型或 CHAT。
 * 用 .content() 取纯文本 + 手动解析 JSON（百炼兼容模式不支持 Spring AI 结构化输出/tool calling）。
 */
@Component
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IntentClassifier(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 分类用户输入。
     *
     * @param prompt 用户输入
     * @return 分类决策（taskType 为 null 表示纯聊天）
     */
    public IntentDecision classify(String prompt) {
        String raw = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("用户输入：" + prompt)
                .call()
                .content();
        log.info("意图分类原始输出: {}", raw);
        return parse(raw);
    }

    /** 解析模型输出的 intent JSON，非法输出直接抛异常（由上层 ErrorHandler 处理） */
    @SuppressWarnings("unchecked")
    private IntentDecision parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("意图分类未返回内容");
        }
        String json = stripCodeFence(raw);
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(json, Map.class);
        } catch (Exception exception) {
            throw new IllegalStateException("意图分类输出不是合法 JSON：" + exception.getMessage(), exception);
        }
        Object intent = root.get("intent");
        if (intent == null) {
            throw new IllegalStateException("意图分类缺少 intent 字段");
        }
        String value = intent.toString().trim().toUpperCase();
        if ("CHAT".equals(value)) {
            return IntentDecision.chat();
        }
        try {
            return IntentDecision.task(AiTaskType.valueOf(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("未知意图类型：" + value, exception);
        }
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

    /** 分类决策：taskType 为 null 表示纯聊天 */
    public record IntentDecision(AiTaskType taskType) {

        public boolean isChat() {
            return taskType == null;
        }

        public static IntentDecision chat() {
            return new IntentDecision(null);
        }

        public static IntentDecision task(AiTaskType type) {
            return new IntentDecision(type);
        }
    }

    private static final String SYSTEM_PROMPT = """
            你是意图分类器。判断用户输入属于「画布生成任务」还是「普通聊天/问答」。

            可选 intent（只输出以下之一）：
            - GENERATE_MINDMAP：生成思维导图
            - GENERATE_FLOWCHART：生成流程图
            - STUDY_PLAN：制定学习计划/学习路径
            - PRODUCT_REQUIREMENT：写产品需求文档
            - GENERATE_IMAGE：生成图片/画图
            - DOCUMENT_SUMMARY：总结/摘要/归纳资料
            - CANVAS_STRUCTURE：整理/梳理/结构化画布内容，或删除/清空画布元素
            - CHAT：普通聊天、打招呼、问答、询问，或不属于以上任务

            规则：
            1. 用户明确表达「生成/制作/画/总结/整理/规划」等动作时才归类为任务。
            2. 只是提问、打招呼、闲聊、咨询，归类为 CHAT。
            3. 只输出 JSON，格式：{"intent":"..."}，不要输出任何其他文字。
            """;
}
