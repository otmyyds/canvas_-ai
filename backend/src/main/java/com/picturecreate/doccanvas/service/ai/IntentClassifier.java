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
     * 分类用户输入，拆解为有序的任务计划。
     *
     * @param prompt 用户输入
     * @param hasSelection 当前是否有选中的画布元素（用于区分「编辑选中元素」与「清空画布」）
     * @return 分类决策（plan 为空表示纯聊天）
     */
    public IntentDecision classify(String prompt, boolean hasSelection) {
        String raw = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("用户输入：" + prompt + "\n是否有选中元素：" + (hasSelection ? "是" : "否"))
                .call()
                .content();
        log.info("意图分类原始输出: {}", raw);
        return parse(raw);
    }

    /** 解析模型输出的 plan JSON，非法输出直接抛异常（由上层 ErrorHandler 处理） */
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
        Object planValue = root.get("plan");
        if (!(planValue instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("意图分类缺少 plan 字段");
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
                throw new IllegalStateException("未知意图类型：" + value, exception);
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

    private static final String SYSTEM_PROMPT = """
            你是意图分类器。把用户输入拆解为「画布生成任务」或「普通聊天/问答」。

            可选任务类型（plan 中每一项是以下之一）：
            - GENERATE_MINDMAP：生成思维导图
            - GENERATE_FLOWCHART：生成流程图
            - STUDY_PLAN：制定学习计划/学习路径
            - PRODUCT_REQUIREMENT：写产品需求文档
            - DOCUMENT_SUMMARY：总结/摘要/归纳资料
            - CANVAS_STRUCTURE：整理/梳理/结构化画布内容
            - CLEAR_CANVAS：删除/清空/移除画布中的全部元素
            - EDIT_ELEMENT：修改/丰富/删除选中的画布元素，或给选中的元素添加分支
            - CHAT：普通聊天、打招呼、问答、询问，或不属于以上任务

            规则：
            1. 用户明确表达「生成/制作/画/总结/整理/规划」等动作时才归类为任务。
            2. 表达「删除/清空/移除画布中的全部元素」归类为 CLEAR_CANVAS。
            3. 当「是否有选中元素」为是，且指令是针对选中元素的「修改/丰富/添加分支/删除」时，归类为 EDIT_ELEMENT。
               注意：删除某个/某些选中元素是 EDIT_ELEMENT，删除全部元素才是 CLEAR_CANVAS。
            4. 只是提问、打招呼、闲聊、咨询，归类为 CHAT。
            5. 多步骤指令要按执行顺序拆成多个任务，例如：
               「先删除之前的流程图，再根据上述文本生成新的流程图」→ {"plan":["CLEAR_CANVAS","GENERATE_FLOWCHART"]}；
               「总结资料并整理画布」→ {"plan":["DOCUMENT_SUMMARY","CANVAS_STRUCTURE"]}。
            6. 只输出 JSON，格式：{"plan":["类型1","类型2",...]}。单个任务就一个元素；纯聊天输出 {"plan":["CHAT"]}。不要输出任何其他文字。
            """;
}
