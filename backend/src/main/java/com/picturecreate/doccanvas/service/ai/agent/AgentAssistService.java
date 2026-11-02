package com.picturecreate.doccanvas.service.ai.agent;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.picturecreate.doccanvas.api.dto.AiTaskDtos.AiTaskResponse;
import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.domain.AgentRole;
import com.picturecreate.doccanvas.domain.AgentRun;
import com.picturecreate.doccanvas.domain.AgentRunStatus;
import com.picturecreate.doccanvas.service.AiTaskService;
import com.picturecreate.doccanvas.service.BoardService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.ai.PromptCatalog;
import com.picturecreate.doccanvas.service.ai.llm.LlmGateway;
import com.picturecreate.doccanvas.service.ai.llm.LlmOperation;
import com.picturecreate.doccanvas.service.ai.llm.LlmRequest;
import com.picturecreate.doccanvas.service.ai.llm.LlmResult;
import com.picturecreate.doccanvas.service.ai.llm.TaskRuntime;

/**
 * /assist 的同步预检：用小预算 Supervisor 决定「直接回复」还是「创建后台计划任务」。
 * 预检不写画布；任何修改都由 start_plan 创建的步骤在后台逐步执行。
 */
@Service
public class AgentAssistService {

    private static final Logger log = LoggerFactory.getLogger(AgentAssistService.class);

    private final LlmGateway gateway;
    private final PromptCatalog promptCatalog;
    private final AgentToolRegistry toolRegistry;
    private final AgentRunRecorder recorder;
    private final AiTaskService aiTaskService;
    private final BoardService boardService;
    private final AppProperties properties;

    public AgentAssistService(LlmGateway gateway, PromptCatalog promptCatalog, AgentToolRegistry toolRegistry,
            AgentRunRecorder recorder, AiTaskService aiTaskService, BoardService boardService,
            AppProperties properties) {
        this.gateway = gateway;
        this.promptCatalog = promptCatalog;
        this.toolRegistry = toolRegistry;
        this.recorder = recorder;
        this.aiTaskService = aiTaskService;
        this.boardService = boardService;
        this.properties = properties;
    }

    /**
     * 执行一次预检。
     *
     * @return 直接回复文本，或已创建的后台任务
     */
    public AssistOutcome assist(UUID boardId, CurrentUser user, String prompt, List<String> selectedShapeIds) {
        // 归属校验放在调用模型之前：非本人画布直接 404，不消耗模型额度
        boardService.getOwned(boardId, user);
        AppProperties.Agent agentConfig = properties.ai().agent();
        TaskRuntime runtime = TaskRuntime.start(null, agentConfig.preflightMaxModelCalls(),
                agentConfig.preflightMaxToolCalls(), agentConfig.preflightMaxDurationSeconds(),
                agentConfig.maxTotalTokens(), () -> {
                }, null);
        AgentRun run = recorder.startRun(null, null, null, user.id(), boardId, AgentRole.SUPERVISOR, null, null);
        UUID runId = run.getId();
        AgentExecutionContext context = new AgentExecutionContext(null, boardId, user, null, runId,
                AgentRole.SUPERVISOR, null, prompt, selectedShapeIds, runtime);
        long startNanos = System.nanoTime();
        try {
            String system = promptCatalog.agentPreflightPrompt(boardId, toolRegistry.preflightToolNames(),
                    prompt, selectedShapeIds.isEmpty() ? null : String.join("、", selectedShapeIds));
            LlmRequest request = new LlmRequest(LlmOperation.PREFLIGHT, system, "请判断并处理。",
                    toolRegistry.preflightTools(context), AgentToolContext.of(context), 0.1, runtime,
                    recorder.modelCallListener(runId));
            log.info("预检开始：boardId={} runId={} 需求长度={} 选中节点={}",
                    boardId, runId, prompt.length(), selectedShapeIds.size());
            LlmResult result = gateway.call(request);
            log.info("预检模型返回：boardId={} runId={} 耗时={}ms 模型={} 模型调用={} 工具调用={} tokens={}",
                    boardId, runId, elapsedMs(startNanos), result.model(), runtime.modelCalls(),
                    runtime.toolCalls(), runtime.totalTokens());
            recorder.addUsage(runId, runtime.modelCalls(), runtime.toolCalls(),
                    runtime.promptTokens(), runtime.completionTokens(), runtime.lastModel());
            recorder.finishRun(runId, AgentRunStatus.SUCCEEDED, null, null);
            if (context.startedTaskId() != null) {
                AiTaskResponse task = aiTaskService.get(context.startedTaskId(), user);
                log.info("预检创建任务：boardId={} taskId={} type={} 总耗时={}ms", boardId, task.id(),
                        context.startedTaskType(), elapsedMs(startNanos));
                return new AssistOutcome(null, task);
            }
            // 没有创建任务却留下计划错误：模型没能产出可执行计划，必须把原因返回给用户
            if (context.planError() != null) {
                String reply = "未能生成可执行计划：" + context.planError();
                log.warn("预检计划生成失败：boardId={} runId={} 原因={} 总耗时={}ms", boardId, runId,
                        context.planError(), elapsedMs(startNanos));
                return new AssistOutcome(reply, null);
            }
            String reply = context.replyText() == null ? result.content() : context.replyText();
            log.info("预检直接回复：boardId={} runId={} 回复长度={} 总耗时={}ms", boardId, runId,
                    reply == null ? 0 : reply.length(), elapsedMs(startNanos));
            return new AssistOutcome(reply, null);
        } catch (RuntimeException exception) {
            log.warn("预检失败：boardId={} runId={} 耗时={}ms 原因={}", boardId, runId,
                    elapsedMs(startNanos), exception.getMessage());
            try {
                // 失败的调用也要记账：429/超时同样消耗了上游请求
                recorder.addUsage(runId, runtime.modelCalls(), runtime.toolCalls(),
                        runtime.promptTokens(), runtime.completionTokens(), runtime.lastModel());
                recorder.finishRun(runId, AgentRunStatus.FAILED, null, exception.getMessage());
            } catch (RuntimeException ignored) {
                // 审计失败不覆盖原始异常
            }
            throw exception;
        }
    }

    /** 纳秒差转毫秒，日志统一用毫秒展示 */
    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 预检结果：reply 与 task 二选一 */
    public record AssistOutcome(String reply, AiTaskResponse task) {
    }
}
