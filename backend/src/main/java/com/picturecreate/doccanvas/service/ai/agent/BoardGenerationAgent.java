package com.picturecreate.doccanvas.service.ai.agent;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.domain.AiTaskType;
import com.picturecreate.doccanvas.service.ai.BoardAction;
import com.picturecreate.doccanvas.service.ai.BoardActionValidator;
import com.picturecreate.doccanvas.service.ai.GenerationOutputParser;
import com.picturecreate.doccanvas.service.ai.PromptCatalog;
import com.picturecreate.doccanvas.service.ai.SkillDefinition;
import com.picturecreate.doccanvas.service.ai.llm.LlmGateway;
import com.picturecreate.doccanvas.service.ai.llm.LlmOperation;
import com.picturecreate.doccanvas.service.ai.llm.LlmRequest;
import com.picturecreate.doccanvas.service.ai.llm.LlmResult;

/**
 * 后台生成 Agent：一次模型调用产出单份文档，程序确定性解析为画布动作。
 *
 * 树型任务输出 Markdown 标题大纲，流程图/编辑任务输出单份 JSON；
 * 模型只负责内容，节点层级的父子连线由解析器按标题层级或 JSON 动作生成。
 * 解析或校验失败时把错误回填给模型再试一次，仍失败则任务失败。
 */
@Component
public class BoardGenerationAgent {

    private static final Logger log = LoggerFactory.getLogger(BoardGenerationAgent.class);

    /** 一次生成最多尝试次数：首次 + 带错误反馈重试一次 */
    private static final int MAX_ATTEMPTS = 2;

    private final LlmGateway gateway;
    private final PromptCatalog promptCatalog;
    private final AgentToolRegistry toolRegistry;
    private final AppProperties properties;
    private final AgentRunRecorder recorder;

    public BoardGenerationAgent(LlmGateway gateway, PromptCatalog promptCatalog,
            AgentToolRegistry toolRegistry, AppProperties properties, AgentRunRecorder recorder) {
        this.gateway = gateway;
        this.promptCatalog = promptCatalog;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.recorder = recorder;
    }

    /** 执行一次生成：模型调用 → 解析 → 校验，失败时带错误反馈重试一次 */
    public AgentResult run(AgentExecutionContext context, SkillDefinition skill) {
        AiTaskType taskType = context.taskTypeHint();
        boolean outline = isOutlineTreeTask(taskType);
        List<String> toolNames = toolRegistry.generationToolNames();
        // 提示词只组装一次：重试时只替换 user prompt 里的错误反馈，避免重复读模板与技能
        String system = outline
                ? promptCatalog.generationOutlinePrompt(context.boardId(), taskType, toolNames,
                        context.prompt(), selectedDescription(context), skillBody(skill),
                        context.sessionContext())
                : promptCatalog.generationActionsPrompt(context.boardId(), taskType, toolNames,
                        context.prompt(), selectedDescription(context), skillBody(skill),
                        context.sessionContext());
        long runStart = System.nanoTime();
        log.info("生成开始：taskId={} runId={} 任务类型={} 输出模式={} 选中节点={} 需求长度={} 可用工具={}",
                context.taskId(), context.runId(), taskType, outline ? "markdown" : "json",
                context.selectedShapeIds().size(), length(context.prompt()), toolNames);
        String previousError = null;
        LlmResult llm = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            context.runtime().report(attempt == 1 ? "AI 正在生成内容" : "AI 正在修正生成内容",
                    attempt == 1 ? 40 : 60);
            LlmRequest request = new LlmRequest(LlmOperation.AGENT, system,
                    userPrompt(context, previousError), toolRegistry.generationTools(context),
                    AgentToolContext.of(context), 0.2, context.runtime(),
                    recorder.modelCallListener(context.runId()));
            log.info("开始模型调用：taskId={} 尝试={}/{}", context.taskId(), attempt, MAX_ATTEMPTS);
            long callStart = System.nanoTime();
            llm = gateway.call(request);
            long callMs = elapsedMs(callStart);
            log.info("模型调用返回：taskId={} 尝试={} 耗时={}ms 模型={} tokens={} 输出长度={}",
                    context.taskId(), attempt, callMs, llm.model(), llm.totalTokens(), length(llm.content()));
            // 解析是纯 Java 逻辑，不经过模型；耗时异常通常意味着输出超长
            long parseStart = System.nanoTime();
            List<BoardAction> actions = outline
                    ? GenerationOutputParser.parseOutline(llm.content())
                    : GenerationOutputParser.parseActions(llm.content());
            long parseMs = elapsedMs(parseStart);
            String error = validate(actions, outline);
            log.info("解析与校验完成：taskId={} 尝试={} 耗时={}ms 动作数={} 节点数={} 连线数={} 校验={}",
                    context.taskId(), attempt, parseMs, actions.size(), countCreate(actions), countConnect(actions),
                    error.isEmpty() ? "通过" : error);
            if (error.isEmpty()) {
                log.info("生成完成：taskId={} runId={} 输出模式={} 动作数={} 尝试={} 总耗时={}ms "
                                + "模型调用={} 工具调用={} tokens={}",
                        context.taskId(), context.runId(), outline ? "markdown" : "json", actions.size(), attempt,
                        elapsedMs(runStart), context.runtime().modelCalls(), context.runtime().toolCalls(),
                        context.runtime().totalTokens());
                return AgentResult.modified("AI 已生成 " + actions.size() + " 个画布动作", actions,
                        context.runId(), llm.model(), context.runtime().modelCalls(),
                        context.runtime().toolCalls(), context.runtime().totalTokens());
            }
            previousError = error;
            log.warn("生成结果未通过校验，准备重试：taskId={} 尝试={}/{} 原因={}",
                    context.taskId(), attempt, MAX_ATTEMPTS, error);
        }
        log.error("生成最终失败：taskId={} runId={} 总耗时={}ms 最后原因={}",
                context.taskId(), context.runId(), elapsedMs(runStart), previousError);
        throw new IllegalStateException("AI 生成结果未通过校验：" + previousError);
    }

    /** 空结果直接给出可反馈给模型的错误，其余交给结构校验器；返回空串表示通过 */
    private String validate(List<BoardAction> actions, boolean outline) {
        if (actions.isEmpty()) {
            return outline ? "未从输出中解析出任何标题节点" : "未从输出中解析出任何画布动作";
        }
        return BoardActionValidator.validate(actions, properties.ai().agent().maxNodesPerTask());
    }

    /** 新建节点数，仅用于日志 */
    private long countCreate(List<BoardAction> actions) {
        return actions.stream().filter(BoardAction.CreateShape.class::isInstance).count();
    }

    /** 连线数，仅用于日志 */
    private long countConnect(List<BoardAction> actions) {
        return actions.stream().filter(BoardAction.Connect.class::isInstance).count();
    }

    /** 纳秒差转毫秒，日志统一用毫秒展示 */
    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 字符串长度，null 按 0 处理，避免日志里出现 length(null) 异常 */
    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    /** 树型任务：标题层级直接决定节点树 */
    private boolean isOutlineTreeTask(AiTaskType type) {
        return type == AiTaskType.GENERATE_MINDMAP
                || type == AiTaskType.DOCUMENT_SUMMARY
                || type == AiTaskType.STUDY_PLAN
                || type == AiTaskType.PRODUCT_REQUIREMENT;
    }

    /** 规划任务的 user prompt：任务类型、用户需求与上次失败原因 */
    private String userPrompt(AgentExecutionContext context, String previousError) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("任务类型：").append(context.taskTypeHint()).append('\n');
        prompt.append("用户需求：").append(context.prompt()).append('\n');
        if (previousError != null && !previousError.isBlank()) {
            prompt.append("\n注意：你上一次的输出未通过校验，原因：").append(previousError)
                    .append("。请修复后重新输出，不要重复相同错误。\n");
        }
        return prompt.toString();
    }

    private String selectedDescription(AgentExecutionContext context) {
        if (context.selectedShapeIds().isEmpty()) {
            return null;
        }
        return String.join("、", context.selectedShapeIds());
    }

    private String skillBody(SkillDefinition skill) {
        return skill == null ? null : skill.body();
    }
}
