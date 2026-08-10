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
public class SpringAiGenerationService implements AiGenerationService {

    /** 结构化输出失败后的最大重试次数（计划 9.7：最多反馈修复一次） */
    private static final int MAX_SCHEMA_RETRIES = 1;

    private static final Logger log = LoggerFactory.getLogger(SpringAiGenerationService.class);

    private final ChatClient chatClient;

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

    @Override
    public BoardActionResult generateBoardActions(AiTaskContext context) {
        log.info("========== AI 任务开始 ==========");
        log.info("任务类型: {}", context.type());
        log.info("用户输入: {}", context.prompt());
        log.info("画布卡片数: {}, 资料数: {}", context.cards().size(), context.sources().size());
        if (!context.cards().isEmpty()) {
            log.info("现有卡片: {}", context.cards().stream()
                    .map(c -> c.title() + "[" + c.cardId() + "]").toList());
        }

        // 思维导图：两段式生成（AI1 写文章 → AI2 抽结构），深度与分层更合理
        if (context.type() == com.picturecreate.doccanvas.domain.AiTaskType.GENERATE_MINDMAP) {
            return generateMindmapTwoStage(context);
        }
        // 内联上下文：不用工具收集（百炼兼容性），直接用 prompt 传入卡片和资料
        String toolContext = inlineContext(context.cards(), context.sources());

        // 结构化输出（用 content() 取文本，手动解析 JSON）
        int attempts = 0;
        String lastError = null;
        while (attempts <= MAX_SCHEMA_RETRIES) {
            attempts += 1;
            try {
                String sys = systemPrompt(context);
                String user = userPrompt(context, toolContext, lastError);
                log.info("[生成 attempt {}] system prompt:\n{}", attempts, sys);
                log.info("[生成 attempt {}] user prompt:\n{}", attempts, user);
                String raw = chatClient.prompt()
                        .system(sys)
                        .user(user)
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

    /** 从模型输出的 content 文本中解析 JSON 动作（降级方案：不用 .entity() / json_schema） */
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
     * 从 AI1 文章的标题层级（# ## ###）直接解析思维导图节点。
     * AI2 输出失败时的兜底方案。
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
     * 思维导图两段式生成：
     * AI1 先写一篇结构化的 Markdown 文章（用 # / ## / ### 表达层级，内容自然展开）；
     * AI2 再读文章，按标题层级抽成思维导图节点（create_card + connect），输出 JSON。
     * 这样深度靠 AI1 自由发挥，分层靠 AI2 专注抽取，质量更合理。
     */
    private BoardActionResult generateMindmapTwoStage(AiTaskContext context) {
        log.info("========== 思维导图两段式：AI1 写文章 ==========");
        // 阶段一：AI1 写文章
        String article;
        try {
            String articlePrompt = buildMindmapArticlePrompt(context);
            log.info("[AI1] 文章生成 user prompt:\n{}", articlePrompt);
            article = chatClient.prompt()
                    .system("""
                            你是一位知识组织专家。请根据用户的需求，写一篇结构化的 Markdown 文章。
                            要求：
                            1. 用 # 表示中心主题，## 表示一级分支，### 表示二级分支。
                            2. 每个分支要有实质内容：核心观点、论据、例子或数据。
                            3. 一级分支 5-8 个，每个一级分支下可含 2-4 个二级分支。
                            4. 内容要有深度，不要空泛。标题用 # ## ### 层级，正文用 - 列表。
                            """)
                    .user(articlePrompt)
                    .toolContext(Map.of(
                            BoardReadTools.TASK_ID_KEY, context.taskId().toString(),
                            BoardReadTools.OWNER_ID_KEY, context.ownerId().toString()))
                    .call()
                    .content();
        } catch (Exception exception) {
            throw new IllegalStateException("AI 生成思维导图文章失败：" + exception.getMessage(), exception);
        }
        if (article == null || article.isBlank()) {
            throw new IllegalStateException("AI 未返回思维导图文章内容");
        }
        log.info("[AI1] 文章生成完成，长度 {}:\n{}", article.length(), article);

        // 阶段二：AI2 抽结构
        log.info("========== 思维导图两段式：AI2 抽结构 ==========");
        int attempts = 0;
        String lastError = null;
        while (attempts <= MAX_SCHEMA_RETRIES) {
            attempts += 1;
            try {
                log.info("[AI2 attempt {}] 输入文章:\n{}", attempts, article);
                String raw = chatClient.prompt()
                        .system("""
                                你是思维导图结构化专家。请阅读下面用户提供的 Markdown 文章，把它抽取成思维导图的图形节点和连线。
                                规则：
                                1. # 标题 → 中心主题 create_shape（id=root1，level=0，shape=ellipse）
                                2. ## 标题 → 一级分支 create_shape（id=branch1...，level=1，shape=rectangle），connect 连到 root1
                                3. ### 标题 → 二级叶子 create_shape（id=leaf1...，level=2，shape=oval），connect 连到对应的一级分支 id
                                4. 每个 create_shape 的 title 用该节点标题，x/y 都填 0（后端自动布局）
                                5. connect 带 style="curve"（曲线）
                                6. 只输出 JSON！格式：
                                {"actions":[
                                  {"type":"create_shape","id":"root1","title":"中心","shape":"ellipse","level":0,"x":0,"y":0,"width":180,"height":90},
                                  {"type":"create_shape","id":"branch1","title":"分支","shape":"rectangle","level":1,"x":0,"y":0,"width":140,"height":70},
                                  {"type":"create_shape","id":"leaf1","title":"叶子","shape":"oval","level":2,"x":0,"y":0,"width":120,"height":60},
                                  {"type":"connect","fromCardId":"root1","toCardId":"branch1","style":"curve"},
                                  {"type":"connect","fromCardId":"branch1","toCardId":"leaf1","style":"curve"}
                                ]}
                                """)
                        .user(article)
                        .toolContext(Map.of(
                                BoardReadTools.TASK_ID_KEY, context.taskId().toString(),
                                BoardReadTools.OWNER_ID_KEY, context.ownerId().toString()))
                        .call()
                        .content();
                log.info("[AI2 attempt {}] 模型原始输出:\n{}", attempts, raw);
                List<BoardAction> actions = parseActionsFromContent(raw, context);
                log.info("[AI2 attempt {}] 解析出 {} 个动作: {}", attempts, actions.size(),
                        actions.stream().map(a -> a.getClass().getSimpleName()).toList());
                String error = BoardActionValidator.validate(actions);
                if (error.isEmpty()) {
                    log.info("========== 思维导图两段式完成，共 {} 个动作 ==========", actions.size());
                    return new BoardActionResult(actions,
                            "{\"mode\":\"spring-ai-mindmap\",\"actionCount\":%d,\"attempts\":%d,\"message\":\"思维导图生成完成\"}"
                                    .formatted(actions.size(), attempts), attempts);
                }
                // AI2 输出非 JSON 或校验失败 → 降级：直接从 AI1 文章的标题层级解析节点
                log.warn("[AI2 attempt {}] 校验失败: {}，降级为从文章标题解析", attempts, error);
                List<BoardAction> fallback = parseMindmapFromArticle(article);
                if (!fallback.isEmpty()) {
                    String fallbackError = BoardActionValidator.validate(fallback);
                    if (fallbackError.isEmpty()) {
                        log.info("降级解析成功，从文章提取 {} 个动作", fallback.size());
                        return new BoardActionResult(fallback,
                                "{\"mode\":\"spring-ai-mindmap-fallback\",\"actionCount\":%d,\"attempts\":%d,\"message\":\"思维导图生成完成（文章解析）\"}"
                                        .formatted(fallback.size(), attempts), attempts);
                    }
                }
                lastError = error;
                if (attempts > MAX_SCHEMA_RETRIES) {
                    throw new IllegalStateException("思维导图动作校验失败：" + error);
                }
            } catch (IllegalStateException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException("AI 抽取思维导图结构失败：" + exception.getMessage(), exception);
            }
        }
        throw new IllegalStateException("思维导图动作校验失败：" + lastError);
    }

    /** 思维导图文章的 user prompt：注入用户需求 + 画布上下文 */
    private String buildMindmapArticlePrompt(AiTaskContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户需求：").append(context.prompt()).append("\n\n");
        if (context.sources() != null && !context.sources().isEmpty()) {
            prompt.append("参考的上传资料内容：\n");
            for (var src : context.sources()) {
                prompt.append("\n===== 文件：").append(src.filename()).append(" =====\n");
                prompt.append(src.content());
                prompt.append('\n');
            }
        }
        return prompt.toString();
    }

    private String systemPrompt(AiTaskContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是单人 AI 文档画布的规划模型。\n");
        prompt.append("**你必须只输出 JSON 对象！不要输出任何文字、解释、代码围栏。直接输出 JSON。**\n");
        prompt.append("JSON 格式：{\"actions\":[{\"type\":\"create_card\",\"title\":\"...\",\"markdown\":\"...\",\"cardType\":\"SUMMARY\",\"x\":120,\"y\":120}, ...]}\n");
        prompt.append("cardType 取值：SUMMARY、IDEA、SOURCE、QUESTION、NOTE。\n");
        prompt.append("id 是可选的临时 id（string），供本计划内 connect 引用新卡。\n");
        prompt.append(contentDepthConstraint(context.type().name()));
        prompt.append("connect 的 fromCardId/toCardId 用已有卡片的 id 或本次 create_card 的 id。\n");
        prompt.append("一次任务输出 3-10 个动作（结构图多输出一些），动作之间有意义。\n");
        prompt.append(structurePrompt(context.type().name()));
        prompt.append(chatGuard(context.type().name()));
        if (context.skill() != null) {
            prompt.append("\n当前技能说明：\n").append(context.skill().body()).append('\n');
            prompt.append("可调用工具范围：").append(String.join("、", context.skill().allowedTools())).append('\n');
        }
        return prompt.toString();
    }

    /**
     * 按任务类型决定卡片正文的深度要求：
     * 内容类任务（总结/学习/产品）→ 详实深度；结构图/流程图 → 精简扼要。
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
     * 对非生产型意图（闲聊/询问）加约束，防止把"你好"当任务执行。
     */
    private String chatGuard(String taskType) {
        return """
                如果用户的输入不是要生成内容或整理画布的任务（例如只是打招呼、自我介绍、闲聊），
                你仍然输出一个最小的 create_card 动作，卡片标题为 "AI 助手"，内容为简短友好的文字回复，
                不要再输出额外的大量动作。
                """;
    }

    private String structurePrompt(String taskType) {
        if ("GENERATE_MINDMAP".equals(taskType)) {
            return "\n任务类型是生成思维导图：\n1. 第一个 create_card（id=root1）是中心主题。\n2. 输出 5-8 个分支 create_card，id 依次为 branch1..branchN，每个用 markdown 写深度要点。\n3. 每个分支通过 connect（fromCardId=root1, toCardId=branchN）连到中心。\n4. 各卡片 x/y 中心放 (500,400)，分支围绕 180-360px 间距排列。\n";
        }
        if ("GENERATE_FLOWCHART".equals(taskType)) {
            return "\n任务类型是生成流程图：\n1. 第一个 create_card（id=start1）是开始节点，最后一个（id=end1）是结束节点。\n2. 中间输出 4-6 个 create_card 作为处理/判断节点。\n3. 用 connect 按流程顺序串联。\n4. 各卡片 y 从 100 起向下 180px 间距，x 在 200-600 间。\n";
        }
        return "";
    }

    private String userPrompt(AiTaskContext context, String toolContext, String previousError) {
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
        if (context.sources() != null && !context.sources().isEmpty()) {
            prompt.append("资料库中已解析的文本如下，请优先依据这些内容回答：\n");
            for (AiTaskContext.SourceContext source : context.sources()) {
                prompt.append("\n===== 文件：").append(source.filename()).append(" =====\n");
                prompt.append(source.content());
                prompt.append('\n');
            }
        } else {
            prompt.append("\n当前画布没有已解析的资料。\n");
        }
        if (toolContext != null && !toolContext.isBlank()) {
            prompt.append("\n工具收集到的额外上下文：\n").append(toolContext).append('\n');
        }
        if (previousError != null && !previousError.isBlank()) {
            prompt.append("\n注意：你上一次的输出未通过校验，原因：").append(previousError)
                    .append("。请修复后重新输出，不要重复相同错误。\n");
        }
        return prompt.toString();
    }

    /**
     * 用内联上下文收集代替工具调用：不调 collectContext（百炼 qwen-plus 在 Spring AI 工具调用上有端到端兼容问题）。
     * 直接把 cards/sources 数据注入 prompt，后续可接回工具调用。
     */
    private String inlineContext(List<AiTaskContext.CardContext> cards, List<AiTaskContext.SourceContext> sources) {
        StringBuilder sb = new StringBuilder();
        if (cards != null && !cards.isEmpty()) {
            sb.append("画布现有卡片：\n");
            for (var card : cards) {
                sb.append("- [").append(card.cardId()).append("] ").append(card.title())
                        .append("：").append(card.content() == null ? "" : card.content().replace('\n', ' '))
                        .append('\n');
            }
        }
        if (sources != null && !sources.isEmpty()) {
            sb.append("已解析资料片段：\n");
            for (var src : sources) {
                sb.append(src.filename()).append("：").append(src.content()).append('\n');
            }
        }
        return sb.toString();
    }
}