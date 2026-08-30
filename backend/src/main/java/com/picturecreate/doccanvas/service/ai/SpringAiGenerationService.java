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

import com.picturecreate.doccanvas.domain.AiTaskType;

@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "spring-ai")
/**
 * 真实模型（Spring AI + 百炼 DashScope）的生成服务。
 *
 * 职责：接收「AI 任务上下文」（画布图形节点、上传资料、用户提示），
 * 调用大模型生成一批「画布动作」（BoardAction：建图形节点/连线/删节点），
 * 返回给 AiTaskService 统一校验后落库。
 *
 * 为什么不用 .entity() 结构化输出？
 * - 百炼 DashScope 对 Spring AI 的 json_schema 响应格式支持不完整（返回 end-of-input）
 * - 因此改为 .content() 取纯文本，再用 parseActionsFromContent 手动解析 JSON
 * 工具调用（只读）：
 * - get_board_context / list_source_files / search_document_chunks 已注册，
 *   模型在规划时按需调用以获取画布现状与资料（同步 .call() 路径下百炼兼容 mode 可用）
 * - 写操作仍走 JSON 动作方案（BoardAction），由后端校验后落库，不暴露给模型直接写库
 */
public class SpringAiGenerationService implements AiGenerationService {

    /** 结构化输出失败后的最大重试次数（最多反馈修复一次） */
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
     * 1. 打印任务上下文日志（类型/输入/图形节点数/资料数）
     * 2. 树型任务（思维导图/总结/学习计划/产品需求）→ 单次写大纲 + 从标题层级确定性解析节点
     * 3. 流程图 → 单次调用：拼 system+user prompt → 模型输出 JSON → 手动解析动作
     * 4. 校验动作；失败则携带错误反馈重试（MAX_SCHEMA_RETRIES 次）
     *
     * @param context 任务上下文（画布图形节点、资料、用户提示、skill）
     * @return 结构化动作列表 + 元信息
     */
    @Override
    public BoardActionResult generateBoardActions(AiTaskContext context) {
        log.info("========== AI 任务开始 ==========");
        log.info("任务类型: {}", context.type());
        log.info("用户输入: {}", context.prompt());
        log.info("画布图形节点数: {}（资料由模型通过工具按需检索）", context.shapes().size());

        // 树型任务：单次输出标题大纲 + 标题层级解析（不二次调模型抽结构）
        if (isOutlineTreeTask(context.type())) {
            return generateOutlineTree(context);
        }
        // 整理画布：确定性重新布局已有图形节点，不调模型生成新内容（布局由 AiTaskService 完成）
        if (context.type() == AiTaskType.CANVAS_STRUCTURE) {
            log.info("========== 整理画布：跳过模型生成，直接重新布局 ==========");
            return BoardActionResult.empty(
                    "{\"mode\":\"spring-ai\",\"actionCount\":0,\"attempts\":0,\"message\":\"整理画布\"}");
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
                String error = BoardActionValidator.validate(actions, context);
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

    /** 树型任务（走标题大纲 → 确定性解析图形节点树） */
    private boolean isOutlineTreeTask(AiTaskType type) {
        return type == AiTaskType.GENERATE_MINDMAP
                || type == AiTaskType.DOCUMENT_SUMMARY
                || type == AiTaskType.STUDY_PLAN
                || type == AiTaskType.PRODUCT_REQUIREMENT;
    }

    /**
     * 从模型输出的 content 文本解析出结构化动作列表。
     *
     * 兼容情况：
     * - 模型可能输出带 ```json ... ``` 代码围栏的文本 → 先剥离围栏
     * - 模型可能输出纯 JSON 对象 {"actions":[...]} → 解析 actions 数组
     * - 每个动作按 type 字段分发为对应的 BoardAction 子类（create_shape/connect/delete_shape）
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
                    case "create_shape" -> actions.add(new BoardAction.CreateShape(
                            str(map.get("id")),
                            str(map.get("title")),
                            str(map.get("shape"), "ellipse"),
                            intValue(map.get("level"), 0),
                            dbl(map.get("x"), 0), dbl(map.get("y"), 0),
                            dbl(map.get("width"), 140), dbl(map.get("height"), 60)));
                    case "connect" -> actions.add(new BoardAction.Connect(
                            str(map.get("fromShapeId")), str(map.get("toShapeId")),
                            str(map.get("label")),
                            str(map.get("style")),
                            str(map.get("color")),
                            intValue(map.get("weight"), 2)));
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
     * 从 AI 标题大纲的标题层级（# ## ###）直接解析图形节点与连线。
     * 树型任务的唯一结构来源：大纲由 AI 写成固定层级，标题直接承载知识点，此处确定性解析，不再二次调用模型。
     */
    @SuppressWarnings("unchecked")
    private List<BoardAction> parseMindmapFromArticle(String article) {
        if (article == null || article.isBlank()) {
            return List.of();
        }
        List<BoardAction> actions = new java.util.ArrayList<>();
        // parentAtLevel[level] 记录每层最近出现的节点 id（level=井号数-1），供 connect 到父节点
        String[] parentAtLevel = new String[8];
        int nodeCount = 0;

        for (String line : article.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("#")) {
                continue;
            }
            int hashes = 0;
            while (hashes < trimmed.length() && trimmed.charAt(hashes) == '#') {
                hashes += 1;
            }
            // 标题井号后必须有空格分隔
            if (hashes >= trimmed.length() || trimmed.charAt(hashes) != ' ') {
                continue;
            }
            String title = trimmed.substring(hashes + 1).trim();
            if (title.isEmpty()) {
                continue;
            }
            int level = hashes - 1;
            nodeCount += 1;
            String id = "node" + nodeCount;
            // 节点类型与尺寸按层级：0=ellipse 中心，1=rectangle 分支，>=2=oval 叶子
            String shape = level == 0 ? "ellipse" : level == 1 ? "rectangle" : "oval";
            double w = level == 0 ? 200 : level == 1 ? 180 : 160;
            double h = level == 0 ? 96 : level == 1 ? 72 : 60;
            actions.add(new BoardAction.CreateShape(id, title, shape, level, 0, 0, w, h));
            // 连接到上一层最近出现的父节点
            if (level > 0 && parentAtLevel[level - 1] != null) {
                actions.add(new BoardAction.Connect(parentAtLevel[level - 1], id, "", "curve", null, level == 1 ? 2 : 1));
            }
            parentAtLevel[level] = id;
            // 同层新节点会重置其子树，清空更深层记录
            for (int i = level + 1; i < parentAtLevel.length; i++) {
                parentAtLevel[i] = null;
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
     * 树型任务生成：单次模型调用输出标题大纲，再从标题层级确定性解析图形节点。
     *
     * 为什么只调一次模型？
     * - 模型输出带 # / ## / ### 标题层级的 Markdown 大纲，知识点直接写进标题；
     * - 节点与连线由 parseMindmapFromArticle 直接从标题层级解析，无需二次调用模型；
     * - 二次"抽结构"调用既多余（标题层级已定），又容易因输入过长触发上游连接重置。
     *
     * 坐标由后端 GraphLayoutService 重算，模型只出结构不排坐标。
     *
     * @param context 任务上下文
     * @return 图形节点 + 连线动作
     */
    private BoardActionResult generateOutlineTree(AiTaskContext context) {
        log.info("========== 树型任务：AI 输出标题大纲 ==========");
        String article;
        try {
            String articlePrompt = buildOutlineArticlePrompt(context);
            log.info("[AI1] 标题大纲生成 user prompt:\n{}", articlePrompt);
            article = chatClient.prompt()
                    .system(outlineArticleSystemPrompt(context))
                    .user(articlePrompt)
                    .toolContext(toolContext(context))
                    .call()
                    .content();
        } catch (Exception exception) {
            throw new IllegalStateException("AI 生成大纲失败：" + exception.getMessage(), exception);
        }
        if (article == null || article.isBlank()) {
            throw new IllegalStateException("AI 未返回大纲内容");
        }
        log.info("[AI1] 标题大纲生成完成，长度 {}:\n{}", article.length(), article);
        // 从标题层级确定性解析节点与连线，不再二次调用模型抽结构
        List<BoardAction> actions = parseMindmapFromArticle(article);
        if (actions.isEmpty()) {
            throw new IllegalStateException("未能从大纲解析出图形结构");
        }
        log.info("========== 树型任务完成，解析出 {} 个动作 ==========", actions.size());
        return new BoardActionResult(actions,
                "{\"mode\":\"spring-ai-outline\",\"actionCount\":%d,\"message\":\"图形结构生成完成\"}"
                        .formatted(actions.size()));
    }

    /**
     * 构造树型任务写大纲阶段的 system prompt。
     * 说明结构要求（按任务类型给出一句产出要求），并引导模型在存在上传资料时先用工具检索再写大纲。
     *
     * @param context 任务上下文（用于注入 boardId）
     * @return system prompt 字符串
     */
    private String outlineArticleSystemPrompt(AiTaskContext context) {
        return """
                你是一位知识组织专家。请根据用户的需求，输出一份结构化的 Markdown 标题大纲。
                要求：
                1. 用标题井号数量表示层级：# 中心主题，## 一级分支，### 二级，#### 三级，##### 四级（最多五层）。
                2. 每个标题必须是有实质信息的精炼短语（核心概念、观点或结论），8-16 字左右，
                   禁止只写"概述""介绍""背景"等空泛词，也禁止只写单个词。
                3. 一级分支 5-8 个，每级分支下可含 2-4 个子分支，逐层展开。
                4. 只输出 # 到 ##### 标题行，不要输出任何正文、列表、前言、解释或代码围栏。
                """
                + outlineRequirement(context.type().name())
                + "当前画布 boardId：" + context.boardId() + "\n"
                + toolInstruction();
    }

    /** 按任务类型给出一句产出要求，引导大纲内容贴合任务语义 */
    private String outlineRequirement(String taskType) {
        return switch (taskType) {
            case "DOCUMENT_SUMMARY" -> "本任务是总结资料：把资料核心内容归纳为要点分支。\n";
            case "STUDY_PLAN" -> "本任务是学习计划：把计划组织为阶段 → 步骤的树形结构。\n";
            case "PRODUCT_REQUIREMENT" -> "本任务是产品需求：围绕目标、用户、范围、里程碑展开分支。\n";
            default -> "本任务是思维导图：以中心主题为根，逐层辐射展开知识点。\n";
        };
    }

    /**
     * 构造树型任务写大纲阶段的 user prompt。
     * 只注入用户需求；资料内容由模型通过 search_document_chunks 工具按需检索，
     * 不注入画布现有节点（避免干扰主题）。
     *
     * @param context 任务上下文
     * @return 用户 prompt 字符串
     */
    private String buildOutlineArticlePrompt(AiTaskContext context) {
        return "用户需求：" + context.prompt() + "\n\n"
                + "如有上传资料，请先用 search_document_chunks 检索相关内容，再据此输出标题大纲。";
    }

    /**
     * 构造规划模型的 system prompt（非树型任务共用，当前主要是流程图）。
     * 约束模型：只输出 JSON 动作（create_shape/connect/delete_shape）、技能说明。
     *
     * @param context 任务上下文（含 skill）
     * @return system prompt 字符串
     */
    private String systemPrompt(AiTaskContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是单人 AI 文档画布的规划模型。\n");
        prompt.append("**你必须只输出 JSON 对象！不要输出任何文字、解释、代码围栏。直接输出 JSON。**\n");
        prompt.append("JSON 格式：{\"actions\":[{\"type\":\"create_shape\",\"id\":\"node1\",\"title\":\"...\",\"shape\":\"ellipse\",\"level\":0,\"x\":120,\"y\":120}, {\"type\":\"connect\",\"fromShapeId\":\"node1\",\"toShapeId\":\"node2\",\"label\":\"...\"}, {\"type\":\"delete_shape\",\"shapeId\":\"...\"}]}\n");
        prompt.append("shape 取值：ellipse（中心/结束）、rectangle（处理/分支）、oval（叶子/判断）。\n");
        prompt.append("id 是可选的临时 id（string），供本计划内 connect 引用新节点。\n");
        prompt.append("connect 的 fromShapeId/toShapeId 用已有图形节点的 id 或本次 create_shape 的 id。\n");
        prompt.append("删除画布元素时输出 delete_shape（shapeId 为已有图形节点 id），id 以上文注入的真实 id 为准，不要编造。\n");
        prompt.append("一次任务输出 3-10 个动作（结构图多输出一些），动作之间有意义。\n");
        prompt.append("当前画布 boardId：").append(context.boardId()).append('\n');
        prompt.append(toolInstruction());
        prompt.append(structurePrompt(context.type().name()));
        prompt.append(editElementPrompt(context.type().name()));
        if (context.skill() != null) {
            prompt.append("\n当前技能说明：\n").append(context.skill().body()).append('\n');
            prompt.append("可调用工具范围：").append(String.join("、", context.skill().allowedTools())).append('\n');
        }
        return prompt.toString();
    }

    /**
     * 流程图任务的专用约束（单次调用路径）。
     * 思维导图/总结/学习/产品需求已改走 generateOutlineTree，不经过此路径。
     *
     * @param taskType 任务类型字符串
     * @return 结构图约束文本（非流程图返回空串）
     */
    private String structurePrompt(String taskType) {
        if ("GENERATE_FLOWCHART".equals(taskType)) {
            return "\n任务类型是生成流程图：\n1. 第一个 create_shape（id=start1，shape=ellipse）是开始节点，最后一个（id=end1，shape=ellipse）是结束节点。\n2. 中间输出 4-6 个 create_shape 作为处理/判断节点（shape=rectangle 或 oval）。\n3. 用 connect 按流程顺序串联（fromShapeId/toShapeId 用各节点的 id）。\n";
        }
        return "";
    }

    /**
     * 编辑选中元素任务的专用约束。
     * 用户已选中若干图形节点，指令是修改/丰富/添加分支/删除，模型据此输出针对性动作。
     *
     * @param taskType 任务类型字符串
     * @return 编辑约束文本（非编辑任务返回空串）
     */
    private String editElementPrompt(String taskType) {
        if (!"EDIT_ELEMENT".equals(taskType)) {
            return "";
        }
        return """
                任务类型是编辑选中的画布元素：
                1. 用户已选中若干图形节点，其真实 id 见 user prompt 的「选中」段落。
                2. 根据用户指令，对选中元素输出对应动作：
                   - 删除选中图形节点 → delete_shape（shapeId=选中节点真实 id）
                   - 给选中图形节点添加分支 → create_shape + connect（fromShapeId=选中节点 id，toShapeId=新节点 id）
                3. 扩展只能用 create_shape（shape=oval，level=选中节点 level+1），绝对禁止其他节点类型。
                4. 动作里的 id 必须用 user prompt 注入的真实 id，不要编造。
                5. 新生成的内容要有深度（要点/论据），不要空泛。
                """;
    }

    /**
     * 构造规划阶段的 user prompt：任务类型 + 用户需求 + 画布现有图形节点（真实 id）+ 重试反馈。
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
        if (context.shapes() != null && !context.shapes().isEmpty()) {
            prompt.append("画布中现有图形节点（shapeId 以此为准）：\n");
            for (AiTaskContext.ShapeContext shape : context.shapes()) {
                prompt.append("- [").append(shape.shapeId()).append("] ")
                        .append(shape.title()).append(" (").append(shape.shape()).append(", level=")
                        .append(shape.level()).append(")\n");
            }
            prompt.append('\n');
        }
        if (context.selectedShapes() != null && !context.selectedShapes().isEmpty()) {
            prompt.append("用户当前选中的图形节点（编辑操作目标，id 以此为准）：\n");
            for (AiTaskContext.ShapeContext shape : context.selectedShapes()) {
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
                - get_board_context：获取画布标题与图形节点摘要
                - list_source_files：列出画布已上传的资料文件
                - search_document_chunks：从资料向量索引检索相关片段
                不要编造节点或资料内容，检索结果以工具返回为准。
                """;
    }
}
