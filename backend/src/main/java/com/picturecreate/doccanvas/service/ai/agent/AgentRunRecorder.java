package com.picturecreate.doccanvas.service.ai.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturecreate.doccanvas.domain.AgentRole;
import com.picturecreate.doccanvas.domain.AgentRun;
import com.picturecreate.doccanvas.domain.AgentRunStatus;
import com.picturecreate.doccanvas.domain.AgentStep;
import com.picturecreate.doccanvas.domain.AgentStepType;
import com.picturecreate.doccanvas.repository.AgentRunRepository;
import com.picturecreate.doccanvas.repository.AgentStepRepository;

/**
 * Agent 运行轨迹记录器。
 * 负责创建/结束运行、追加步骤和累计 token；失败只影响本次记录，不反向驱动任务状态。
 */
@Service
public class AgentRunRecorder {

    /** error_message 列长度 */
    private static final int ERROR_MAX_LENGTH = 1000;

    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final AgentMetrics metrics;
    private final ObjectMapper objectMapper;

    public AgentRunRecorder(AgentRunRepository runRepository, AgentStepRepository stepRepository,
            AgentMetrics metrics, ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    /** 创建一条运行中记录 */
    @Transactional
    public AgentRun startRun(UUID taskId, UUID sessionId, UUID parentRunId, UUID ownerId, UUID boardId,
            AgentRole role, String branchKey, String model) {
        AgentRun run = new AgentRun(taskId, sessionId, parentRunId, ownerId, boardId, role, branchKey, model);
        AgentRun saved = runRepository.save(run);
        metrics.runStarted(role);
        return saved;
    }

    /** 追加一条步骤；seq 在同一运行内单调递增 */
    @Transactional
    public AgentStep recordStep(UUID runId, AgentStepType type, String toolName, Object input,
            Object output, long latencyMs, String errorMessage) {
        int seq = stepRepository.findMaxSeq(runId) + 1;
        AgentStep step = new AgentStep(runId, seq, type, toolName, toJson(input), toJson(output),
                (int) Math.max(0, latencyMs), truncate(errorMessage));
        AgentStep saved = stepRepository.save(step);
        metrics.step(type, latencyMs);
        return saved;
    }

    /** 累加本次运行的模型调用、工具调用和 token 用量 */
    @Transactional
    public void addUsage(UUID runId, int modelCalls, int toolCalls, int promptTokens, int completionTokens) {
        AgentRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 运行不存在：" + runId));
        run.addUsage(modelCalls, toolCalls, promptTokens, completionTokens);
        runRepository.save(run);
        metrics.tokens(run.getRole(), promptTokens, completionTokens);
    }

    /** 关闭任务下仍在运行中的记录，用于进程重启和僵尸任务回收 */
    public void finishRunningRuns(UUID taskId, AgentRunStatus status, String errorMessage) {
        for (AgentRun run : runRepository.findByTaskIdAndStatus(taskId, AgentRunStatus.RUNNING)) {
            finishRun(run.getId(), status, null, errorMessage);
        }
    }

    /** 结束运行；耗时按 startedAt 到当前时间计算 */
    @Transactional
    public void finishRun(UUID runId, AgentRunStatus status, String resultJson, String errorMessage) {
        AgentRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 运行不存在：" + runId));
        run.finish(status, resultJson, truncate(errorMessage));
        runRepository.save(run);
        Instant startedAt = run.getStartedAt();
        Duration duration = startedAt == null ? Duration.ZERO : Duration.between(startedAt, Instant.now());
        metrics.runFinished(run.getRole(), status, duration);
    }

    /** 任意对象转 JSON 树；null 保持为 null */
    private JsonNode toJson(Object value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }

    /** 错误信息截断到列长度 */
    private String truncate(String errorMessage) {
        if (errorMessage == null || errorMessage.length() <= ERROR_MAX_LENGTH) {
            return errorMessage;
        }
        return errorMessage.substring(0, ERROR_MAX_LENGTH);
    }
}
