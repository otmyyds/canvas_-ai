package com.picturecreate.doccanvas.service.ai;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "spring-ai")
/**
 * 真实模型（Spring AI + 百炼 DashScope）的生成服务。
 *
 * 职责：接收「AI 任务上下文」（画布卡片、上传资料、用户提示），
 * 调用大模型生成一批「画布动作」（BoardAction：建卡/建图形/连线/删卡），
 * 返回给 AiTaskService 统一校验后落库。
 *
 * 为什么不用 .entity() 结构化输出？
 * - 百炼 DashScope 对 Spring AI 的 json_schema 响应格式支持不完整（返回 end-of-input）
 * - 因此改为 .content() 取纯文本，再用 parseActionsFromContent 手动解析 JSON
 * 工具调用（只读）：
 * - get_board_context / get_card_content / list_source_files / search_document_chunks 已注册，
 *   模型在规划时按需调用以获取画布现状与资料（同步 .call() 路径下百炼兼容 mode 可用）
 * - 写操作仍走 JSON 动作方案（BoardAction），由后端校验后落库，不暴露给模型直接写库
 */
public class SpringAiGenerationService implements AiGenerationService {

    /** 结构化输出失败后的最大重试次数（计划 9.7：最多反馈修复一次） */
    private static final int MAX_SCHEMA_RETRIES = 1;

    private static final Logger log = LoggerFactory.getLogger(SpringAiGenerationService.class);

    /** Spring AI 的聊天客户端（构建时注入只读工具回调，规划时由模型按需调用） */
    private final ChatClient chatClient;

    /**
     * 构造：注册只读工具（get_board_context / list_source_files / search_document_chunks 等），
     * 构建带默认工具的 ChatClient。
     *
     * @param builder Spring AI 自动注入的 ChatClient.Builder
     * @param boardReadTools 只读工具（经 @Tool 注解暴露给模型）
     */
    public SpringAiGenerationService(ChatClient.Builder builder, BoardReadTools boardReadTools) {
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(boardReadTools)
                .build();
        ToolCallback[] callbacks = provider.getToolCallbacks();
        log.info("已注册 AI 只读工具：{}", Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()).toList());
        this.chatClient = builder
                .defaultToolCallbacks(callbacks)
                .build();
    }

    /**
     * 生成画布动作的入口（AiTaskService 调用）。
     *
     * 流程：
     * 1. 打印任务上下文日志（类型/输入/卡片数/资料数）
     * 2. 若是「思维导图」→ 单次写文章 + 从标题层级确定性解析节点
     * 3. 其他任务 → 单次调用：拼 system+user prompt → 模型输出 JSON → 手动解析动作
     * 4. 校验动作；失败则携带错误反馈重试（MAX_SCHEMA_RETRIES 次）
     *
     * @param context 任务上下文（画布卡片、资料、用户提示、skill）
     * @return 结构化动作列表 + 元信息
     */
    @Override
    public BoardActionResult generateBoardActions(AiTaskContext context) {
        log.info("========== AI 任务开始 ==========");
        log.info("任务类型: {}", context.type());
        log.info("用户输入: {}", context.prompt());
        log.info("画布卡片数: {}, 图形数: {}（资料由模型通过工具按需检索）", context.cards().size(),
                context.shapes().size());
        if (!context.cards().isEmpty()) {
            log.info("现有卡片: {}", context.cards().stream()
                    .map(c -> c.title() + "[" + c.cardId() + "]").toList());
        }

        // 思维导图：单次写文章 + 标题层级解析（不二次调模型抽结构）
        if (context.type() == com.picturecreate.doccanvas.domain.AiTaskType.GENERATE_MINDMAP) {
            return generateMindmap(context);
        }
        // 结构化输出（用 content() 取文本，手动解析 JSON；只读上下文由模型按需调用工具获取）
        int attempts = 0;
        String lastError = null;
        while (attempts <= MAX_SCHEMA_RETRIES) {
            attempts += 1;
            try {
                String sys = systemPrompt(context);
                String user = userPrompt(context, lastError);
                log.info("[生成 attempt {}] system prompt:\n{}", attempts, sys);
                log.info("[生成 attempt {}] user prompt:\n{}", attempts, user);
                String raw = chatClient.prompt()
                        .system(sys)
                        .user(user)
                        .toolContext(toolContext(context))
                        .call()
                        .content();
                log.info("[生成 attempt {}] 模型原始输出:\n{}", attempts, raw);
                // 手动解析：content() 返回的是文本，model 在 system 引导下输出的 JSON
                List<BoardAction> actions = parseActionsFromContent(raw, context);
                log.info("[生成 attempt {}] 解析出 {} 个动作: {}", attempts, actions.size(),
                        actions.stream().map(a -> a.getClass().getSimpleName()).toList());
                String error = BoardActionValidator.validate(actions);
                if (error.isEmpty()) {
                    log.info("========== AI 任务完成，共 {} 个动作 ==========", actions.size());
                    return new BoardActionResult(actions,
                            "{\"mode\":\"spring-ai\",\"actionCount\":%d,\"attempts\":%d,\"message\":\"模型生成完成\"}"
                                    .formatted(actions.size(), attempts), attempts);
                }
                lastError = error;
                log.warn("[生成 attempt {}] 校验失败: {}", attempts, error);
                if (attempts > MAX_SCHEMA_RETRIES) {
                    throw new IllegalStateException("AI 动作校验失败：" + error);
                }
            } catch (Exception exception) {
                // 如果异常已经是 IllegalState，直接抛出
                if (exception instanceof IllegalStateException) {
                    throw (IllegalStateException) exception;
                }
                String detail = exception.getMessage() == null ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                throw new IllegalStateException("调用 AI 模型失败：" + detail, exception);
            }
        }
        throw new IllegalStateException("AI 动作校验失败：" + lastError);
    }

    /**
     * 从模型输出的 content 文本解析出结构化动作列表。
     *
     * 兼容情况：
     * - 模型可能输出带 ```json ... ``` 代码围栏的文本 → 先剥离围栏
     * - 模型可能输出纯 JSON 对象 {"actions":[...]} → 解析 actions 数组
     * - 每个动作按 type 字段分发为对应的 BoardAction 子类（create_card/create_shape/connect 等）
     * - 无法解析或字段缺失 → 返回空列表（由上层重试或降级）
     *
     * @param content 模型原始输出文本
     * @param context 任务上下文（透传，供必要信息）
     * @return 解析出的动作列表
     */
    @SuppressWarnings("unchecked")
    private List<BoardAction> parseActionsFromContent(String content, AiTaskContext context) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        try {
            // 可能被包裹在 ```json ... ``` 中，先尝试清理
            String json = content.trim();
            if (json.startsWith("```")) {
                int end = json.lastIndexOf("```");
                if (end > 3) {
                    json = json.substring(3, end).trim();
                    if (json.startsWith("json")) {
                        json = json.substring(4).trim();
                    }
                }
            }
            // 解析 JSON 为 Map
            Map<String, Object> root = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
            Object actionsNode = root.get("actions");
            if (!(actionsNode instanceof List<?> list)) {
                return List.of();
            }
            List<BoardAction> actions = new java.util.ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                String type = map.get("type") == null ? "" : map.get("type").toString();
                switch (type) {
                    case "create_card" -> actions.add(new BoardAction.CreateCard(
                            str(map.get("id")),
                            str(map.get("title")), str(map.get("markdown")),
                            str(map.get("cardType"), "SUMMARY"),
                            dbl(map.get("x"), 120), dbl(map.get("y"), 120),
                            dbl(map.get("width"), 340), dbl(map.get("height"), 220),
                            List.of()));
                    case "create_shape" -> actions.add(new BoardAction.CreateShape(
                            str(map.get("id")),
                            str(map.get("title")),
                            str(map.get("shape"), "ellipse"),
                            intValue(map.get("level"), 0),
                            dbl(map.get("x"), 0), dbl(map.get("y"), 0),
                            dbl(map.get("width"), 140), dbl(map.get("height"), 60)));
                    case "update_card" -> actions.add(new BoardAction.UpdateCard(
                            str(map.get("cardId")),
                            str(map.get("title")), str(map.get("markdown"))));
                    case "connect" -> actions.add(new BoardAction.Connect(
                            str(map.get("fromCardId")), str(map.get("toCardId")),
                            str(map.get("label")),
                            str(map.get("style")),
                            str(map.get("color")),
                            intValue(map.get("weight"), 2)));
                    case "delete_card" -> actions.add(new BoardAction.DeleteCard(
                            str(map.get("cardId"))));
                    case "delete_shape" -> actions.add(new BoardAction.DeleteShape(
                            str(map.get("shapeId"))));
                    default -> {
                        // 忽略未知动作
                    }
                }
            }
            return actions;
        } catch (Exception exception) {
            log.warn("解析模型输出 JSON 失败：{} content: {}", exception.getMessage(),
                    content == null ? "" : content.substring(0, Math.min(200, content.length())));
            return List.of();
        }
    }

    /**
     * 从 AI1 文章的标题层级（# ## ###）直接解析思维导图节点与连线。
     * 思维导图的唯一结构来源：文章由 AI 写成固定层级，此处确定性解析，不再二次调用模型。
     */
    @SuppressWarnings("unchecked")
    private List<BoardAction> parseMindmapFromArticle(String article) {
        if (article == null || article.isBlank()) {
            return List.of();
        }
        List<BoardAction> actions = new java.util.ArrayList<>();
        List<String> branchIds = new java.util.ArrayList<>();
        String rootId = "root1";
        String currentBranch = null;
        int branchCount = 0;
        int leafCount = 0;

        for (String line : article.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                // 中心主题（# 单井号）
                String title = trimmed.substring(2).trim();
                if (title.isEmpty()) {
                    continue;
                }
                actions.add(new BoardAction.CreateShape(rootId, title, "ellipse", 0, 0, 0, 180, 90));
            } else if (trimmed.startsWith("## ") && !trimmed.startsWith("### ")) {
                // 一级分支
                String title = trimmed.substring(3).trim();
                if (title.isEmpty()) {
                    continue;
                }
                branchCount += 1;
                String id = "branch" + branchCount;
                actions.add(new BoardAction.CreateShape(id, title, "rectangle", 1, 0, 0, 140, 70));
                actions.add(new BoardAction.Connect(rootId, id, "", "curve", null, 2));
                currentBranch = id;
                branchIds.add(id);
            } else if (trimmed.startsWith("### ") && currentBranch != null) {
                // 二级叶子
                String title = trimmed.substring(4).trim();
                if (title.isEmpty()) {
                    continue;
                }
                leafCount += 1;
                String id = "leaf" + leafCount;
                actions.add(new BoardAction.CreateShape(id, title, "oval", 2, 0, 0, 120, 60));
                actions.add(new BoardAction.Connect(currentBranch, id, "", "curve", null, 1));
            }
        }
        return actions;
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private double dbl(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return fallback;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }

    /**
     * 思维导图生成：单次模型调用写文章，再从标题层级确定性解析节点。
     *
     * 为什么只调一次模型？
     * - 模型写一篇带 # / ## / ### 标题层级的 Markdown 文章，深度自然展开；
     * - 节点与连线由 parseMindmapFromArticle 直接从标题层级解析，无需二次调用模型；
     * - 二次"抽结构"调用既多余（标题层级已定），又容易因输入文章过长触发上游连接重置。
     *
     * 坐标由后端 GraphLayoutService 重算（layoutMindmap3），模型只出结构不排坐标。
     *
     * @param context 任务上下文
     * @return 思维导图节点 + 连线动作
     */
    private BoardActionResult generateMindmap(AiTaskContext context) {
        log.info("========== 思维导图：AI 写文章 ==========");
        String article;
        try {
            String articlePrompt = buildMindmapArticlePrompt(context);
            log.info("[AI1] 文章生成 user prompt:\n{}", articlePrompt);
            article = chatClient.prompt()
                    .system(mindmapArticleSystemPrompt(context))
                    .user(articlePrompt)
                    .toolContext(toolContext(context))
                    .call()
                    .content();
        } catch (Exception exception) {
            throw new IllegalStateException("AI 生成思维导图文章失败：" + exception.getMessage(), exception);
        }
        if (article == null || article.isBlank()) {
            throw new IllegalStateException("AI 未返回思维导图文章内容");
        }
        log.info("[AI1] 文章生成完成，长度 {}:\n{}", article.length(), article);
        // 从标题层级确定性解析节点与连线，不再二次调用模型抽结构
        List<BoardAction> actions = parseMindmapFromArticle(article);
        if (actions.isEmpty()) {
            throw new IllegalStateException("未能从文章解析出思维导图结构");
        }
        log.info("========== 思维导图完成，解析出 {} 个动作 ==========", actions.size());
        return new BoardActionResult(actions,
                "{\"mode\":\"spring-ai-mindmap\",\"actionCount\":%d,\"message\":\"思维导图生成完成\"}"
                        .formatted(actions.size()));
    }

    /**
     * 构造思维导图 AI1 写文章阶段的 system prompt。
     * 说明结构要求，并引导模型在存在上传资料时先用工具检索再写文章。
     *
     * @param context 任务上下文（用于注入 boardId）
     * @return system prompt 字符串
     */
    private String mindmapArticleSystemPrompt(AiTaskContext context) {
        return """
                你是一位知识组织专家。请根据用户的需求，写一篇结构化的 Markdown 文章。
                要求：
                1. 用 # 表示中心主题，## 表示一级分支，### 表示二级分支。
                2. 每个分支要有实质内容：核心观点、论据、例子或数据。
                3. 一级分支 5-8 个，每个一级分支下可含 2-4 个二级分支。
                4. 内容要有深度，不要空泛。标题用 # ## ### 层级，正文用 - 列表。
                5. 直接输出文章，不要输出前言、解释或代码围栏。
                """
                + "当前画布 boardId：" + context.boardId() + "\n"
                + toolInstruction();
    }

    /**
     * 构造思维导图 AI1 写文章阶段的 user prompt。
     * 只注入用户需求；资料内容由 AI1 通过 search_document_chunks 工具按需检索，
     * 不注入画布卡片（避免干扰主题）。
     *
     * @param context 任务上下文
     * @return 用户 prompt 字符串
     */
    private String buildMindmapArticlePrompt(AiTaskContext context) {
        return "用户需求：" + context.prompt() + "\n\n"
                + "如有上传资料，请先用 search_document_chunks 检索相关内容，再据此写文章。";
    }

    /**
     * 构造规划模型的 system prompt（所有非思维导图任务共用）。
     * 约束模型：只输出 JSON 动作、cardType 取值、深度要求、技能说明。
     *
     * @param context 任务上下文（含 skill）
     * @return system prompt 字符串
     */
    private String systemPrompt(AiTaskContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是单人 AI 文档画布的规划模型。\n");
        prompt.append("**你必须只输出 JSON 对象！不要输出任何文字、解释、代码围栏。直接输出 JSON。**\n");
        prompt.append("JSON 格式：{\"actions\":[{\"type\":\"create_card\",\"title\":\"...\",\"markdown\":\"...\",\"cardType\":\"SUMMARY\",\"x\":120,\"y\":120}, ...]}\n");
        prompt.append("cardType 取值：SUMMARY、IDEA、SOURCE、QUESTION、NOTE。\n");
        prompt.append("id 是可选的临时 id（string），供本计划内 connect 引用新卡。\n");
        prompt.append(contentDepthConstraint(context.type().name()));
        prompt.append("connect 的 fromCardId/toCardId 用已有卡片的 id 或本次 create_card 的 id。\n");
        prompt.append("删除画布元素时输出 delete_card（cardId 为已有卡片 id）或 delete_shape（shapeId 为已有图形节点 id），id 以上文注入的真实 id 为准，不要编造。\n");
        prompt.append("一次任务输出 3-10 个动作（结构图多输出一些），动作之间有意义。\n");
        prompt.append("当前画布 boardId：").append(context.boardId()).append('\n');
        prompt.append(toolInstruction());
        prompt.append(structurePrompt(context.type().name()));
        prompt.append(chatGuard(context.type().name()));
        if (context.skill() != null) {
            prompt.append("\n当前技能说明：\n").append(context.skill().body()).append('\n');
            prompt.append("可调用工具范围：").append(String.join("、", context.skill().allowedTools())).append('\n');
        }
        return prompt.toString();
    }

    /**
     * 按任务类型决定卡片正文的深度要求。
     * 内容类任务（总结/学习/产品）→ 详实（≥300 字，章节结构）
     * 结构图类（思维/流程/整理）→ 精简（每张 1-3 句话，关键词/短语）
     *
     * @param taskType 任务类型字符串
     * @return 深度约束文本
     */
    private String contentDepthConstraint(String taskType) {
        if ("DOCUMENT_SUMMARY".equals(taskType) || "STUDY_PLAN".equals(taskType)
                || "PRODUCT_REQUIREMENT".equals(taskType)) {
            return """
                    卡片正文 markdown 要有深度：章节标题、要点、论据、实例/数据，不少于 300 字。
                    """;
        }
        return """
                卡片正文 markdown 要短小精悍，每张卡片 1-3 句话即可。
                不要写大段文字、不要写长章节。
                思维导图分支用关键词或短语，流程图节点用一句话描述步骤。
                """;
    }

    /**
     * 非生产型意图兜底：防止模型把闲聊（你好/你是谁）当任务执行并生成大量卡片。
     * 若输入非任务意图，只输出一张"AI 助手"回复卡。
     *
     * @param taskType 任务类型字符串（当前未使用，保留接口一致性）
     * @return 兜底约束文本
     */
    private String chatGuard(String taskType) {
        return """
                如果用户的输入不是要生成内容或整理画布的任务（例如只是打招呼、自我介绍、闲聊），
                你仍然输出一个最小的 create_card 动作，卡片标题为 "AI 助手"，内容为简短友好的文字回复，
                不要再输出额外的大量动作。
                注意：删除/清空/移除画布元素属于整理画布的任务，必须输出 delete_card / delete_shape 动作，
                不要当成闲聊输出 AI 助手卡。
                """;
    }

    /**
     * 结构图任务的专用约束（思维导图/流程图）。
     * 注意：思维导图实际走 generateMindmapTwoStage，此方法对 MINDMAP 分支不再触发；
     * 主要服务于 GENERATE_FLOWCHART（单次调用路径）。
     *
     * @param taskType 任务类型字符串
     * @return 结构图约束文本（非结构图返回空串）
     */
    private String structurePrompt(String taskType) {
        if ("GENERATE_MINDMAP".equals(taskType)) {
            return "\n任务类型是生成思维导图：\n1. 第一个 create_card（id=root1）是中心主题。\n2. 输出 5-8 个分支 create_card，id 依次为 branch1..branchN，每个用 markdown 写深度要点。\n3. 每个分支通过 connect（fromCardId=root1, toCardId=branchN）连到中心。\n4. 各卡片 x/y 中心放 (500,400)，分支围绕 180-360px 间距排列。\n";
        }
        if ("GENERATE_FLOWCHART".equals(taskType)) {
            return "\n任务类型是生成流程图：\n1. 第一个 create_card（id=start1）是开始节点，最后一个（id=end1）是结束节点。\n2. 中间输出 4-6 个 create_card 作为处理/判断节点。\n3. 用 connect 按流程顺序串联。\n4. 各卡片 y 从 100 起向下 180px 间距，x 在 200-600 间。\n";
        }
        return "";
    }

    /**
     * 构造规划阶段的 user prompt：任务类型 + 用户需求 + 画布现有卡片/图形（真实 id）+ 重试反馈。
     * 资料内容不在 prompt 内联，由模型通过 search_document_chunks 工具按需检索。
     *
     * @param context 任务上下文
     * @param previousError 上次校验失败的错误（重试时附带，引导模型修复）
     * @return user prompt 字符串
     */
    private String userPrompt(AiTaskContext context, String previousError) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("任务类型：").append(context.type()).append('\n');
        prompt.append("用户需求：").append(context.prompt()).append("\n\n");
        if (context.cards() != null && !context.cards().isEmpty()) {
            prompt.append("画布中现有卡片（cardId 以此为准）：\n");
            for (AiTaskContext.CardContext card : context.cards()) {
                prompt.append("- [").append(card.cardId()).append("] ")
                        .append(card.title()).append(" (").append(card.cardType()).append(")：")
                        .append(card.content() == null ? "" : card.content().replace('\n', ' '))
                        .append('\n');
            }
            prompt.append('\n');
        }
        if (context.shapes() != null && !context.shapes().isEmpty()) {
            prompt.append("画布中现有图形节点（shapeId 以此为准）：\n");
            for (AiTaskContext.ShapeContext shape : context.shapes()) {
                prompt.append("- [").append(shape.shapeId()).append("] ")
                        .append(shape.title()).append(" (").append(shape.shape()).append(", level=")
                        .append(shape.level()).append(")\n");
            }
            prompt.append('\n');
        }
        if (previousError != null && !previousError.isBlank()) {
            prompt.append("\n注意：你上一次的输出未通过校验，原因：").append(previousError)
                    .append("。请修复后重新输出，不要重复相同错误。\n");
        }
        return prompt.toString();
    }

    /**
     * 构造工具调用的上下文，把 taskId / ownerId 传给 BoardReadTools，
     * 使其能解析当前任务归属的用户与画布（替代服务端预取内联上下文）。
     *
     * @param context 任务上下文
     * @return 工具调用上下文（taskId + ownerId）
     */
    private Map<String, Object> toolContext(AiTaskContext context) {
        return Map.of(
                BoardReadTools.TASK_ID_KEY, context.taskId().toString(),
                BoardReadTools.OWNER_ID_KEY, context.ownerId().toString());
    }

    /**
     * 向模型说明可用的只读工具及使用时机。
     * 引导模型在涉及画布现有内容或上传资料时，先调用工具检索，再输出最终 JSON 动作方案。
     *
     * @return 工具使用说明文本
     */
    private String toolInstruction() {
        return """
                当任务涉及画布现有内容或上传资料时，先调用工具检索再回答：
                - get_board_context：获取画布标题与卡片摘要
                - get_card_content：读取指定卡片的完整正文
                - list_source_files：列出画布已上传的资料文件
                - search_document_chunks：从资料向量索引检索相关片段
                不要编造卡片或资料内容，检索结果以工具返回为准。
                """;
    }
}