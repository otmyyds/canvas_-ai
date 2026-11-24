package com.picturecreate.doccanvas.service.ai.agent;

import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.domain.AgentRole;
import com.picturecreate.doccanvas.domain.AgentRun;
import com.picturecreate.doccanvas.domain.AgentRunStatus;
import com.picturecreate.doccanvas.domain.AiTaskType;
import com.picturecreate.doccanvas.service.ai.BoardAction;
import com.picturecreate.doccanvas.service.ai.GenerationOutputParser;
import com.picturecreate.doccanvas.service.ai.PromptCatalog;
import com.picturecreate.doccanvas.service.ai.SkillDefinition;
import com.picturecreate.doccanvas.service.ai.llm.LlmGateway;
import com.picturecreate.doccanvas.service.ai.llm.LlmOperation;
import com.picturecreate.doccanvas.service.ai.llm.LlmRequest;
import com.picturecreate.doccanvas.service.ai.llm.LlmResult;
import com.picturecreate.doccanvas.service.ai.llm.TaskAbortedException;
import com.picturecreate.doccanvas.service.ai.llm.TaskRuntime;

/**
 * 分支 Worker：只展开一个分支，输出一级子节点标题。
 *
 * 每个分支独立一条 BRANCH_WORKER 运行记录和独立预算；预算的每次扣减同时计入父计划预算。
 * 解析/校验失败时把错误回填给模型重试一次；模型调用异常由 LlmGateway 自己重试与降级。
 */
@Profile("!lite")
@Component
public class BranchWorkerAgent {

    private static final Logger log = LoggerFactory.getLogger(BranchWorkerAgent.class);

    /** 单分支最多尝试次数：首次 + 带错误反馈重试一次 */
    private static final int MAX_ATTEMPTS = 2;

    /** 节点标题上限，与 shapes.title 列长度一致 */
    private static final int TITLE_MAX_LENGTH = 255;

    private final LlmGateway gateway;
    private final PromptCatalog promptCatalog;
    private final AgentToolRegistry toolRegistry;
    private final AppProperties properties;
    private final AgentRunRecorder recorder;

    public BranchWorkerAgent(LlmGateway gateway, PromptCatalog promptCatalog, AgentToolRegistry toolRegistry,
            AppProperties properties, AgentRunRecorder recorder) {
        this.gateway = gateway;
        this.promptCatalog = promptCatalog;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.recorder = recorder;
    }

    /**
     * 执行一个分支。中断向上抛出由计划执行器收口；其它失败返回 FAILED 结果，
     * 由委派方按 allowPartial 决定整体是否失败。
     */
    public BranchResult run(AgentExecutionContext parentContext, BranchSpec branch, String rootTitle,
            String globalPrompt, SkillDefinition skill) {
        AppProperties.Agent config = properties.ai().agent();
        TaskRuntime runtime = TaskRuntime.branch(parentContext.runtime(), config.branchMaxModelCalls(),
                config.branchMaxToolCalls(), config.branchMaxDurationSeconds(), config.branchMaxTokens());
        AgentRun run = recorder.startRun(parentContext.taskId(), parentContext.sessionId(),
                parentContext.runId(), parentContext.user().id(), parentContext.boardId(), AgentRole.BRANCH_WORKER,
                branch.key(), null);
        AiTaskType taskType = parentContext.taskTypeHint();
        List<String> toolNames = toolRegistry.generationToolNames();
        String system = promptCatalog.branchWorkerPrompt(taskType, toolNames, globalPrompt, skillBody(skill),
                rootTitle, branch.title(), branch.goal(), branch.maxChildren());
        AgentExecutionContext branchContext = new AgentExecutionContext(parentContext.taskId(),
                parentContext.boardId(), parentContext.user(), parentContext.sessionId(), run.getId(),
                AgentRole.BRANCH_WORKER, taskType, system, List.of(), runtime, null);
        log.info("分支开始：taskId={} runId={} key={} 标题={} 子节点上限={}", parentContext.taskId(), run.getId(),
                branch.key(), branch.title(), branch.maxChildren());
        try {
            List<String> children = generate(branchContext, branch, system);
            recordUsageQuietly(run.getId(), runtime);
            finishRunQuietly(run.getId(), AgentRunStatus.SUCCEEDED, null);
            log.info("分支完成：runId={} key={} 子节点数={}", run.getId(), branch.key(), children.size());
            return BranchResult.ok(branch, children);
        } catch (TaskAbortedException exception) {
            recordUsageQuietly(run.getId(), runtime);
            finishRunQuietly(run.getId(), AgentRunStatus.ABORTED, "任务被用户中断");
            throw exception;
        } catch (RuntimeException exception) {
            String error = message(exception);
            recordUsageQuietly(run.getId(), runtime);
            finishRunQuietly(run.getId(), AgentRunStatus.FAILED, error);
            log.warn("分支失败：runId={} key={} error={}", run.getId(), branch.key(), error);
            return BranchResult.failed(branch, error);
        }
    }

    /** 模型调用 + 解析 + 校验；解析类错误重试一次，模型异常直接失败 */
    private List<String> generate(AgentExecutionContext context, BranchSpec branch, String system) {
        String previousError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            context.runtime().check();
            LlmRequest request = new LlmRequest(LlmOperation.AGENT, system,
                    userPrompt(context, branch, previousError), toolRegistry.generationTools(context),
                    AgentToolContext.of(context), 0.2, context.runtime(),
                    recorder.modelCallListener(context.runId()));
            LlmResult llm = gateway.call(request);
            try {
                return parseChildren(llm.content(), branch.maxChildren());
            } catch (IllegalArgumentException exception) {
                previousError = exception.getMessage();
                log.warn("分支输出未通过校验，准备重试：runId={} key={} 尝试={}/{} 原因={}", context.runId(),
                        branch.key(), attempt, MAX_ATTEMPTS, previousError);
            }
        }
        throw new IllegalStateException("分支输出未通过校验：" + previousError);
    }

    /** 只接受 ## 级标题；数量与标题唯一性在本层校验 */
    private List<String> parseChildren(String content, int maxChildren) {
        List<BoardAction.CreateShape> creates = new ArrayList<>();
        for (BoardAction action : GenerationOutputParser.parseOutline(content)) {
            if (action instanceof BoardAction.CreateShape createShape) {
                creates.add(createShape);
            }
        }
        if (creates.isEmpty()) {
            throw new IllegalArgumentException("未解析出任何 ## 子节点");
        }
        List<String> titles = new ArrayList<>();
        Set<String> normalized = new HashSet<>();
        for (BoardAction.CreateShape shape : creates) {
            if (shape.level() != 1) {
                throw new IllegalArgumentException("只能输出 ## 一级子节点，不要输出 # 或 ### 标题");
            }
            String title = shape.title() == null ? "" : shape.title().trim();
            if (title.isEmpty()) {
                throw new IllegalArgumentException("存在空标题子节点");
            }
            if (title.length() > TITLE_MAX_LENGTH) {
                throw new IllegalArgumentException("子节点标题超过 " + TITLE_MAX_LENGTH + " 字：" + title);
            }
            if (!normalized.add(normalize(title))) {
                throw new IllegalArgumentException("子节点标题重复：" + title);
            }
            titles.add(title);
        }
        if (titles.size() > maxChildren) {
            throw new IllegalArgumentException("子节点数超过上限 " + maxChildren + "，实际 " + titles.size());
        }
        return List.copyOf(titles);
    }

    /** 归一化标题：去空白、转小写，用于合并阶段去重 */
    static String normalize(String title) {
        return title == null ? "" : title.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String userPrompt(AgentExecutionContext context, BranchSpec branch, String previousError) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("任务类型：").append(context.taskTypeHint()).append('\n');
        prompt.append("分支：").append(branch.title()).append('\n');
        prompt.append("分支目标：").append(branch.goal()).append('\n');
        if (previousError != null && !previousError.isBlank()) {
            prompt.append("\n注意：你上一次的输出未通过校验，原因：").append(previousError)
                    .append("。请修复后重新输出。\n");
        }
        return prompt.toString();
    }

    private String skillBody(SkillDefinition skill) {
        return skill == null ? null : skill.body();
    }

    private void recordUsageQuietly(UUID runId, TaskRuntime runtime) {
        try {
            recorder.addUsage(runId, runtime.modelCalls(), runtime.toolCalls(), runtime.promptTokens(),
                    runtime.completionTokens(), runtime.lastModel());
        } catch (RuntimeException exception) {
            log.warn("写入分支用量失败：runId={} error={}", runId, exception.getMessage());
        }
    }

    private void finishRunQuietly(UUID runId, AgentRunStatus status, String errorMessage) {
        try {
            recorder.finishRun(runId, status, null, errorMessage);
        } catch (RuntimeException exception) {
            log.warn("结束分支运行记录失败：runId={} status={} error={}", runId, status, exception.getMessage());
        }
    }

    private String message(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
