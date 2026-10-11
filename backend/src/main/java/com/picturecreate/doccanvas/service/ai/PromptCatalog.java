package com.picturecreate.doccanvas.service.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * 提示词统一管理：提示词文案外置到 resources/prompts/*.st，用 Spring AI PromptTemplate 加载渲染。
 * 改提示词无需改代码。动态部分（日期、工具列表、画布标题、资料片段、用户输入）由本类拼好后作为占位符传入。
 *
 * 注意：.st 模板以 {key} 为占位符，字面 JSON 花括号需转义为 \{ 和 \}（见 classification-system.st / board-action.st）。
 */
@Component
public class PromptCatalog {

    private final PromptTemplate classificationTemplate;
    private final PromptTemplate boardActionTemplate;
    private final PromptTemplate editElementTemplate;
    private final PromptTemplate outlineIntroTemplate;
    private final PromptTemplate outlineUserTemplate;
    private final PromptTemplate toolInstructionTemplate;
    private final PromptTemplate webSearchPolicyTemplate;
    private final PromptTemplate currentDateTemplate;
    private final PromptTemplate chatSystemTemplate;

    public PromptCatalog(
            @Value("classpath:prompts/classification-system.st") Resource classificationResource,
            @Value("classpath:prompts/board-action.st") Resource boardActionResource,
            @Value("classpath:prompts/edit-element.st") Resource editElementResource,
            @Value("classpath:prompts/outline-intro.st") Resource outlineIntroResource,
            @Value("classpath:prompts/outline-user.st") Resource outlineUserResource,
            @Value("classpath:prompts/tool-instruction.st") Resource toolInstructionResource,
            @Value("classpath:prompts/web-search-policy.st") Resource webSearchPolicyResource,
            @Value("classpath:prompts/current-date.st") Resource currentDateResource,
            @Value("classpath:prompts/chat-system.st") Resource chatSystemResource) {
        this.classificationTemplate = new PromptTemplate(read(classificationResource));
        this.boardActionTemplate = new PromptTemplate(read(boardActionResource));
        this.editElementTemplate = new PromptTemplate(read(editElementResource));
        this.outlineIntroTemplate = new PromptTemplate(read(outlineIntroResource));
        this.outlineUserTemplate = new PromptTemplate(read(outlineUserResource));
        this.toolInstructionTemplate = new PromptTemplate(read(toolInstructionResource));
        this.webSearchPolicyTemplate = new PromptTemplate(read(webSearchPolicyResource));
        this.currentDateTemplate = new PromptTemplate(read(currentDateResource));
        this.chatSystemTemplate = new PromptTemplate(read(chatSystemResource));
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("读取提示词模板失败：" + resource.getFilename(), exception);
        }
    }

    /** 意图分类系统提示词 */
    public String classificationSystemPrompt() {
        return classificationTemplate.render();
    }

    /** JSON 动作规划系统提示词骨架（流程图/编辑共用，含 JSON 格式与 shape 词汇表） */
    public String boardActionInstruction() {
        return boardActionTemplate.render();
    }

    /** 编辑选中元素任务约束 */
    public String editElementConstraint() {
        return editElementTemplate.render();
    }

    /** 树型任务大纲系统提示词（静态部分，产出要求由 outlineRequirement 按类型补充） */
    public String outlineIntro() {
        return outlineIntroTemplate.render();
    }

    /** 树型任务按类型的一句产出要求 */
    public String outlineRequirement(String taskType) {
        return switch (taskType) {
            case "DOCUMENT_SUMMARY" -> "本任务是总结资料：把资料核心内容归纳为要点分支。\n";
            case "STUDY_PLAN" -> "本任务是学习计划：把计划组织为阶段 → 步骤的树形结构。\n";
            case "PRODUCT_REQUIREMENT" -> "本任务是产品需求：围绕目标、用户、范围、里程碑展开分支。\n";
            default -> "本任务是思维导图：以中心主题为根，逐层辐射展开知识点。\n";
        };
    }

    /** 树型任务大纲用户提示词 */
    public String outlineUserPrompt(String userPrompt) {
        return outlineUserTemplate.render(Map.of("userPrompt", userPrompt));
    }

    /** 工具名 → 用途说明（供按实际暴露的工具生成提示词） */
    private static final Map<String, String> TOOL_DESCRIPTIONS = Map.of(
            "get_board_context", "获取画布标题与图形节点摘要",
            "list_source_files", "列出画布已上传的资料文件",
            "search_document_chunks", "从资料向量索引检索相关片段",
            "web_search", "联网搜索最新信息（版本、日期、近期事件等时效性问题）");

    /** 按本次实际暴露的工具生成说明，只列出真实可用的工具，避免模型调用未暴露的工具 */
    public String toolInstruction(List<String> toolNames) {
        StringBuilder builder = new StringBuilder();
        for (String name : toolNames) {
            String description = TOOL_DESCRIPTIONS.get(name);
            if (description != null) {
                builder.append("- ").append(name).append("：").append(description).append('\n');
            }
        }
        String rendered = toolInstructionTemplate.render(Map.of("tools", builder.toString()));
        if (toolNames.contains("web_search")) {
            rendered += "\n" + webSearchPolicyTemplate.render();
        }
        return rendered;
    }

    /** 当前日期行：让模型解析「今天/明天/去年/最新」等相对时间，避免用过期的训练截止日期 */
    public String currentDateLine() {
        return currentDateTemplate.render(Map.of("date", LocalDate.now().toString()));
    }

    /** 纯文本聊天系统提示词（画布标题 + RAG 资料片段） */
    public String chatSystemPrompt(String boardTitle, List<AiTaskContext.SourceContext> sources) {
        String boardTitleBlock = (boardTitle != null && !boardTitle.isBlank())
                ? "\n当前画布：" + boardTitle + "\n"
                : "";
        StringBuilder sourcesBlock = new StringBuilder();
        if (sources != null && !sources.isEmpty()) {
            sourcesBlock.append("\n与当前问题相关的资料片段（供回答参考）：\n");
            for (AiTaskContext.SourceContext source : sources) {
                sourcesBlock.append("\n===== 片段（").append(source.filename()).append("） =====\n");
                sourcesBlock.append(source.content());
                sourcesBlock.append('\n');
            }
        } else {
            sourcesBlock.append("\n（当前画布没有已解析的上传文档）\n");
        }
        return chatSystemTemplate.render(Map.of(
                "date", currentDateLine(),
                "boardTitle", boardTitleBlock,
                "sources", sourcesBlock.toString()));
    }
}
