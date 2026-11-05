package com.picturecreate.doccanvas.service.ai.agent;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.picturecreate.doccanvas.api.dto.AiTaskDtos.AiTaskResponse;
import com.picturecreate.doccanvas.api.dto.AiTaskDtos.AiTaskStepResponse;
import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.domain.AgentRole;
import com.picturecreate.doccanvas.domain.AgentRun;
import com.picturecreate.doccanvas.domain.AgentRunStatus;
import com.picturecreate.doccanvas.domain.AgentSession;
import com.picturecreate.doccanvas.service.AiTaskService;
import com.picturecreate.doccanvas.service.BoardService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.QuotaExceededException;
import com.picturecreate.doccanvas.service.QuotaService;
import com.picturecreate.doccanvas.service.ai.PromptCatalog;
import com.picturecreate.doccanvas.service.ai.agent.AgentSessionService.SessionMemory;
import com.picturecreate.doccanvas.service.ai.llm.LlmGateway;
import com.picturecreate.doccanvas.service.ai.llm.LlmOperation;
import com.picturecreate.doccanvas.service.ai.llm.LlmRequest;
import com.picturecreate.doccanvas.service.ai.llm.LlmResult;
import com.picturecreate.doccanvas.service.ai.llm.TaskRuntime;

/**
 * /assist 的同步预检：用小预算 Supervisor 决定「直接回复」还是「创建后台计划任务」。
 * 预检不写画布；任何修改都由 start_plan 创建的步骤在后台逐步执行。
 *
 * 每次请求都归属一个会话：用户消息在调用模型前写入，助手回复或“已创建计划”在结束后写入，
 * 更早消息由 AgentSessionService 压缩成滚动摘要，作为下一轮的上下文。
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
    private final AgentSessionService sessionService;
    private final QuotaService quotaService;
    private final AppProperties properties;

    public AgentAssistService(LlmGateway gateway, PromptCatalog promptCatalog, AgentToolRegistry toolRegistry,
            AgentRunRecorder recorder, AiTaskService aiTaskService, BoardService boardService,
            AgentSessionService sessionService, QuotaService quotaService, AppProperties properties) {
        this.gateway = gateway;
        this.promptCatalog = promptCatalog;
        this.toolRegistry = toolRegistry;
        this.recorder = recorder;
        this.aiTaskService = aiTaskService;
        this.boardService = boardService;
        this.sessionService = sessionService;
        this.quotaService = quotaService;
        this.properties = properties;
    }

    /**
     * 执行一次预检。
     *
     * @param sessionId 已有会话 id；为空时新建会话
     * @return 直接回复文本或已创建的后台任务，以及本次使用的会话 id
     */
    public AssistOutcome assist(UUID boardId, CurrentUser user, String prompt, List<String> selectedShapeIds,
            UUID sessionId) {
        // 归属校验与会话归属校验都放在调用模型之前：非本人画布/会话直接 404，不消耗模型额度
        boardService.getOwned(boardId, user);
        // 预检也消耗模型 token，配额用尽时直接 429，避免靠对话绕过配额
        quotaService.assertTokensAvailable(user);
        AgentSession session = sessionService.open(boardId, user, sessionId, prompt);
        // 先读历史再写入本轮用户消息，避免当前需求在提示词里出现两次
        SessionMemory memory = sessionService.loadMemory(session.getId());
        // 从配置中读取预检参数
        AppProperties.Agent agentConfig = properties.ai().agent();
        // 创建开始运行的时间
        TaskRuntime runtime = TaskRuntime.start(null, agentConfig.preflightMaxModelCalls(),
                agentConfig.preflightMaxToolCalls(), agentConfig.preflightMaxDurationSeconds(),
                agentConfig.maxTotalTokens(), () -> {
                }, null);
        // 创建一个运行记录
        AgentRun run = recorder.startRun(null, session.getId(), null, user.id(), boardId, AgentRole.SUPERVISOR,
                null, null);
        UUID runId = run.getId();
        // 写入用户消息
        sessionService.appendUser(session.getId(), prompt, runId);
        // 创建一个执行上下文
        AgentExecutionContext context = new AgentExecutionContext(null, boardId, user, session.getId(), runId,
                AgentRole.SUPERVISOR, null, prompt, selectedShapeIds, runtime, null);
        // 开始预检
        long startNanos = System.nanoTime();
        try {
            String system = promptCatalog.agentPreflightPrompt(boardId, toolRegistry.preflightToolNames(),
                    prompt, selectedShapeIds.isEmpty() ? null : String.join("、", selectedShapeIds),
                    memory.summary(), memory.history());
            LlmRequest request = new LlmRequest(LlmOperation.PREFLIGHT, system, "请判断并处理。",
                    toolRegistry.preflightTools(context), AgentToolContext.of(context), 0.1, runtime,
                    recorder.modelCallListener(runId));
            log.info("预检开始：boardId={} sessionId={} runId={} 需求长度={} 选中节点={}", boardId,
                    session.getId(), runId, prompt.length(), selectedShapeIds.size());
            // 统一调用模型
            LlmResult result = gateway.call(request);
            // 配额异常可能被工具调用层转成错误文本，此时模型仍会正常返回；在记账前把它还原成 429
            if (context.quotaError() != null) {
                throw context.quotaError();
            }
            log.info("预检模型返回：boardId={} runId={} 耗时={}ms 模型={} 模型调用={} 工具调用={} tokens={}",
                    boardId, runId, elapsedMs(startNanos), result.model(), runtime.modelCalls(),
                    runtime.toolCalls(), runtime.totalTokens());
            recorder.addUsage(runId, runtime.modelCalls(), runtime.toolCalls(),
                    runtime.promptTokens(), runtime.completionTokens(), runtime.lastModel());
            recorder.finishRun(runId, AgentRunStatus.SUCCEEDED, null, null);
            if (context.startedTaskId() != null) {
                AiTaskResponse task = aiTaskService.get(context.startedTaskId(), user);
                sessionService.appendAssistant(session.getId(), taskCreatedMessage(task), task.id(), runId);
                log.info("预检创建任务：boardId={} sessionId={} taskId={} type={} 总耗时={}ms", boardId,
                        session.getId(), task.id(), context.startedTaskType(), elapsedMs(startNanos));
                return new AssistOutcome(null, task, session.getId());
            }
            // 没有创建任务却留下计划错误：模型没能产出可执行计划，必须把原因返回给用户
            if (context.planError() != null) {
                String reply = "未能生成可执行计划：" + context.planError();
                sessionService.appendAssistant(session.getId(), reply, null, runId);
                log.warn("预检计划生成失败：boardId={} runId={} 原因={} 总耗时={}ms", boardId, runId,
                        context.planError(), elapsedMs(startNanos));
                return new AssistOutcome(reply, null, session.getId());
            }
            String reply = context.replyText() == null ? result.content() : context.replyText();
            sessionService.appendAssistant(session.getId(), reply, null, runId);
            log.info("预检直接回复：boardId={} runId={} 回复长度={} 总耗时={}ms", boardId, runId,
                    reply == null ? 0 : reply.length(), elapsedMs(startNanos));
            return new AssistOutcome(reply, null, session.getId());
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
            // 工具层可能把配额异常包成 ToolExecutionException，这里剥出原始异常交给 ErrorHandler 映射 429
            QuotaExceededException quota = quotaFrom(exception);
            throw quota == null ? exception : quota;
        }
    }

    /** 沿 cause 链查找配额异常 */
    private QuotaExceededException quotaFrom(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof QuotaExceededException quota) {
                return quota;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    /** 创建计划后的会话摘要：后续“第 N 个分支”等指代依赖这里的步骤顺序 */
    private String taskCreatedMessage(AiTaskResponse task) {
        StringBuilder message = new StringBuilder("已创建执行计划（任务 ").append(task.id()).append("，")
                .append(task.steps().size()).append(" 步）：");
        for (AiTaskStepResponse step : task.steps()) {
            message.append("\n").append(step.seq()).append(". ").append(step.title());
        }
        return message.toString();
    }

    /** 纳秒差转毫秒，日志统一用毫秒展示 */
    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 预检结果：reply 与 task 二选一，sessionId 供前端后续多轮请求复用 */
    public record AssistOutcome(String reply, AiTaskResponse task, UUID sessionId) {
    }
}
