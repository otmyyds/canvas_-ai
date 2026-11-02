package com.picturecreate.doccanvas.service.ai.agent;

import java.util.List;
import java.util.UUID;

import com.picturecreate.doccanvas.domain.AgentRole;
import com.picturecreate.doccanvas.domain.AiTaskType;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.QuotaExceededException;
import com.picturecreate.doccanvas.service.ai.llm.TaskRuntime;

/**
 * 单次 Agent 运行的上下文：归属、工具可访问的范围与结束状态。
 * 通过 Spring AI 的 ToolContext 传给每个工具，工具不直接持有运行时状态。
 */
public final class AgentExecutionContext {

    private final UUID taskId;
    private final UUID boardId;
    private final CurrentUser user;
    private final UUID sessionId;
    private final UUID runId;
    private final AgentRole role;
    private final AiTaskType taskTypeHint;
    private final String prompt;
    private final List<String> selectedShapeIds;
    private final TaskRuntime runtime;
    private final String sessionContext;

    private volatile boolean finished;
    private volatile String replyText;
    private volatile UUID startedTaskId;
    private volatile AiTaskType startedTaskType;
    private volatile String planError;
    /** 创建任务时配额用尽：工具调用层会把它转成工具错误，这里保留原始异常供预检直接返回 429 */
    private volatile QuotaExceededException quotaError;

    public AgentExecutionContext(UUID taskId, UUID boardId, CurrentUser user, UUID sessionId, UUID runId,
            AgentRole role, AiTaskType taskTypeHint, String prompt, List<String> selectedShapeIds,
            TaskRuntime runtime, String sessionContext) {
        this.taskId = taskId;
        this.boardId = boardId;
        this.user = user;
        this.sessionId = sessionId;
        this.runId = runId;
        this.role = role;
        this.taskTypeHint = taskTypeHint;
        this.prompt = prompt;
        this.selectedShapeIds = selectedShapeIds == null ? List.of() : List.copyOf(selectedShapeIds);
        this.runtime = runtime;
        this.sessionContext = sessionContext;
    }

    public UUID taskId() {
        return taskId;
    }

    public UUID boardId() {
        return boardId;
    }

    public CurrentUser user() {
        return user;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public UUID runId() {
        return runId;
    }

    public AgentRole role() {
        return role;
    }

    public AiTaskType taskTypeHint() {
        return taskTypeHint;
    }

    public String prompt() {
        return prompt;
    }

    public List<String> selectedShapeIds() {
        return selectedShapeIds;
    }

    public TaskRuntime runtime() {
        return runtime;
    }

    /** 会话摘要与最近对话文本；分支 Worker 不读取会话，值为 null */
    public String sessionContext() {
        return sessionContext;
    }

    public boolean finished() {
        return finished;
    }

    public String replyText() {
        return replyText;
    }

    /** 记录直接回复：由 reply 工具调用 */
    public void markReplied(String text) {
        this.replyText = text;
        this.finished = true;
    }

    public UUID startedTaskId() {
        return startedTaskId;
    }

    public AiTaskType startedTaskType() {
        return startedTaskType;
    }

    /** 记录预检创建的后台任务 */
    public void markTaskStarted(UUID taskId, AiTaskType type) {
        this.startedTaskId = taskId;
        this.startedTaskType = type;
        this.finished = true;
    }

    public String planError() {
        return planError;
    }

    /** 记录计划校验失败原因：模型重试后仍失败时，由预检把它作为可见回复返回 */
    public void recordPlanError(String error) {
        this.planError = error;
    }

    public QuotaExceededException quotaError() {
        return quotaError;
    }

    /** 记录配额异常，由预检在模型返回后原样抛出，映射为 HTTP 429 */
    public void recordQuotaError(QuotaExceededException error) {
        this.quotaError = error;
        this.finished = true;
    }
}
