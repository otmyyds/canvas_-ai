package com.picturecreate.doccanvas.service.ai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.picturecreate.doccanvas.api.dto.TaskEvent;
import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.domain.AgentRole;
import com.picturecreate.doccanvas.domain.AgentRun;
import com.picturecreate.doccanvas.domain.AgentRunStatus;
import com.picturecreate.doccanvas.domain.AgentStepType;
import com.picturecreate.doccanvas.domain.AiTask;
import com.picturecreate.doccanvas.domain.AiTaskStep;
import com.picturecreate.doccanvas.domain.AiTaskStepStatus;
import com.picturecreate.doccanvas.domain.AiTaskType;
import com.picturecreate.doccanvas.domain.Asset;
import com.picturecreate.doccanvas.domain.BoardEdge;
import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.repository.AiTaskRepository;
import com.picturecreate.doccanvas.repository.AiTaskStepRepository;
import com.picturecreate.doccanvas.repository.AssetRepository;
import com.picturecreate.doccanvas.repository.BoardEdgeRepository;
import com.picturecreate.doccanvas.repository.BoardSnapshotRepository;
import com.picturecreate.doccanvas.repository.ShapeRepository;
import com.picturecreate.doccanvas.service.AiTaskCancellation;
import com.picturecreate.doccanvas.service.AssetService;
import com.picturecreate.doccanvas.service.BoardCheckpointService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.TaskEventPublisher;
import com.picturecreate.doccanvas.service.ai.ApplyReport;
import com.picturecreate.doccanvas.service.ai.BoardAction;
import com.picturecreate.doccanvas.service.ai.BoardActionApplier;
import com.picturecreate.doccanvas.service.ai.BoardActionValidator;
import com.picturecreate.doccanvas.service.ai.ImageGenerationService;
import com.picturecreate.doccanvas.service.ai.SkillDefinition;
import com.picturecreate.doccanvas.service.ai.SkillLoader;
import com.picturecreate.doccanvas.service.ai.llm.TaskAbortedException;
import com.picturecreate.doccanvas.service.ai.llm.TaskRuntime;

/**
 * 计划执行器：按 seq 顺序逐步执行，每一步结束后检查真实画布状态。
 *
 * 整个计划共用一个 TaskRuntime（预算与截止时间），每一步单独一条 AgentRun，
 * 模型调用与工具调用轨迹按步骤归档。任一步失败或任务被中断时：
 * 1. 按计划开始前的检查点恢复画布；
 * 2. 当前步骤落 FAILED/ABORTED，后续步骤落 SKIPPED；
 * 3. 任务落 FAILED/ABORTED，最后才推送事件。
 */
@Component
public class AgentPlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanExecutor.class);

    private final AiTaskRepository taskRepository;
    private final AiTaskStepRepository stepRepository;
    private final ShapeRepository shapeRepository;
    private final BoardEdgeRepository edgeRepository;
    private final AssetRepository assetRepository;
    private final BoardSnapshotRepository snapshotRepository;
    private final BoardCheckpointService checkpointService;
    private final AgentRuntime agentRuntime;
    private final AgentRunRecorder recorder;
    private final SkillLoader skillLoader;
    private final BoardActionApplier actionApplier;
    private final AssetService assetService;
    private final ImageGenerationService imageGenerationService;
    private final TaskEventPublisher eventPublisher;
    private final AiTaskCancellation cancellation;
    private final TransactionTemplate transactionTemplate;
    private final AppProperties properties;

    public AgentPlanExecutor(AiTaskRepository taskRepository, AiTaskStepRepository stepRepository,
            ShapeRepository shapeRepository, BoardEdgeRepository edgeRepository,
            AssetRepository assetRepository, BoardSnapshotRepository snapshotRepository,
            BoardCheckpointService checkpointService, AgentRuntime agentRuntime, AgentRunRecorder recorder,
            SkillLoader skillLoader, BoardActionApplier actionApplier, AssetService assetService,
            ImageGenerationService imageGenerationService, TaskEventPublisher eventPublisher,
            AiTaskCancellation cancellation, TransactionTemplate transactionTemplate, AppProperties properties) {
        this.taskRepository = taskRepository;
        this.stepRepository = stepRepository;
        this.shapeRepository = shapeRepository;
        this.edgeRepository = edgeRepository;
        this.assetRepository = assetRepository;
        this.snapshotRepository = snapshotRepository;
        this.checkpointService = checkpointService;
        this.agentRuntime = agentRuntime;
        this.recorder = recorder;
        this.skillLoader = skillLoader;
        this.actionApplier = actionApplier;
        this.assetService = assetService;
        this.imageGenerationService = imageGenerationService;
        this.eventPublisher = eventPublisher;
        this.cancellation = cancellation;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    /** 执行一个计划任务；异常已在此统一收口，不会再抛给调用方 */
    public void execute(AiTask task, CurrentUser user, List<String> selectedShapeIds) {
        List<AiTaskStep> steps = stepRepository.findByTaskIdOrderBySeqAsc(task.getId());
        if (steps.isEmpty()) {
            finishWithoutCheckpoint(task, "计划没有可执行的步骤");
            return;
        }
        try {
            checkpointService.capture(task);
        } catch (RuntimeException exception) {
            log.error("保存画布检查点失败，计划未执行：taskId={} error={}", task.getId(), exception.getMessage(),
                    exception);
            finishWithoutCheckpoint(task, "保存画布检查点失败：" + message(exception));
            return;
        }
        int total = steps.size();
        AtomicInteger activeIndex = new AtomicInteger();
        AppProperties.Agent config = properties.ai().agent();
        TaskRuntime runtime = TaskRuntime.start(task.getId(), config.maxModelCalls(), config.maxToolCalls(),
                config.maxDurationSeconds(), config.maxTotalTokens(),
                () -> cancellation.assertNotCancelled(task.getId()),
                (progressMessage, percent) -> reportProgress(task, activeIndex.get(), total, percent,
                        progressMessage));
        List<String> pendingPurgeKeys = new ArrayList<>();
        List<String> createdAssetKeys = new ArrayList<>();
        AiTaskStep active = null;
        try {
            for (int index = 0; index < steps.size(); index++) {
                active = steps.get(index);
                activeIndex.set(index);
                runtime.beginStep();
                runtime.check();
                startStep(task, active, index, total);
                executeStep(task, user, active, index, total, runtime, selectedShapeIds, pendingPurgeKeys,
                        createdAssetKeys);
            }
            finishSucceeded(task, steps, pendingPurgeKeys);
        } catch (TaskAbortedException exception) {
            handleAborted(task, active, createdAssetKeys);
        } catch (Exception exception) {
            handleFailed(task, active, exception, createdAssetKeys);
        }
    }

    /** 按步骤类型分派；每一步单独记录一条 AgentRun */
    private void executeStep(AiTask task, CurrentUser user, AiTaskStep step, int index, int total,
            TaskRuntime runtime, List<String> selectedShapeIds, List<String> pendingPurgeKeys,
            List<String> createdAssetKeys) {
        AiTaskType type = step.getStepType();
        if (type == null) {
            throw new IllegalStateException("步骤缺少类型：" + step.getSeq());
        }
        AgentRun run = recorder.startRun(task.getId(), null, null, task.getOwnerId(), task.getBoardId(),
                AgentRole.SUPERVISOR, "step-" + step.getSeq(), null);
        UUID runId = run.getId();
        Usage before = usage(runtime);
        int planProgress = (index + 1) * 100 / total;
        log.info("开始执行计划步骤：taskId={} runId={} seq={} type={} title={}", task.getId(), runId, step.getSeq(),
                type, step.getTitle());
        try {
            switch (type) {
                case CLEAR_CANVAS -> executeClear(task, step, planProgress, pendingPurgeKeys);
                case GENERATE_IMAGE -> executeImage(task, step, planProgress, createdAssetKeys);
                case CANVAS_STRUCTURE -> executeLayout(task, step, planProgress);
                case GENERATE_MINDMAP, GENERATE_FLOWCHART, DOCUMENT_SUMMARY, STUDY_PLAN,
                        PRODUCT_REQUIREMENT, EDIT_ELEMENT -> executeGeneration(task, user, step, planProgress,
                                runtime, selectedShapeIds, runId);
                case AGENT_PLAN -> throw new IllegalStateException("步骤不能是 AGENT_PLAN");
            }
            recordUsageQuietly(runId, runtime, before);
            recordApplyStepQuietly(runId, step);
            finishRunQuietly(runId, AgentRunStatus.SUCCEEDED, null);
            publishStep(task, step);
            log.info("计划步骤完成：taskId={} runId={} seq={} 结果={}", task.getId(), runId, step.getSeq(),
                    step.getResultMessage());
        } catch (TaskAbortedException exception) {
            recordUsageQuietly(runId, runtime, before);
            finishRunQuietly(runId, AgentRunStatus.ABORTED, "任务被用户中断");
            throw exception;
        } catch (RuntimeException exception) {
            recordUsageQuietly(runId, runtime, before);
            finishRunQuietly(runId, AgentRunStatus.FAILED, message(exception));
            log.warn("计划步骤失败：taskId={} runId={} seq={} error={}", task.getId(), runId, step.getSeq(),
                    exception.getMessage());
            throw exception;
        }
    }

    /** 生成类步骤：生成动作，校验、布局、落库与步骤完成放在同一事务 */
    private void executeGeneration(AiTask task, CurrentUser user, AiTaskStep step, int planProgress,
            TaskRuntime runtime, List<String> selectedShapeIds, UUID runId) {
        AiTaskType stepType = step.getStepType();
        SkillDefinition skill = skillLoader.findForTask(stepType.name()).orElse(null);
        String prompt = "总目标：" + task.getPrompt() + "\n本步要求：" + step.getBrief();
        AgentExecutionContext context = new AgentExecutionContext(task.getId(), task.getBoardId(), user, null, runId,
                AgentRole.SUPERVISOR, stepType, prompt, selectedShapeIds, runtime);
        AgentResult result = agentRuntime.generate(context, skill);
        List<BoardAction> actions = result.actions() == null ? List.of() : result.actions();
        int shapesBefore = countShapes(task.getBoardId());
        int edgesBefore = countEdges(task.getBoardId());
        ApplyReport report = applyGenerationStep(task, step, actions, planProgress);
        int shapesAfter = countShapes(task.getBoardId());
        int edgesAfter = countEdges(task.getBoardId());
        int expectedShapes = report.createdShapes() - report.deletedShapes();
        if (shapesAfter - shapesBefore != expectedShapes) {
            throw new IllegalStateException("画布节点数量与预期不符：预期 " + expectedShapes + "，实际 "
                    + (shapesAfter - shapesBefore));
        }
        if (report.createdEdges() > 0 && edgesAfter <= edgesBefore) {
            throw new IllegalStateException("画布连线未按预期增加");
        }
    }

    private ApplyReport applyGenerationStep(AiTask task, AiTaskStep step, List<BoardAction> actions,
            int planProgress) {
        return transactionTemplate.execute(status -> {
            cancellation.assertNotCancelled(task.getId());
            String error = BoardActionValidator.validate(actions, properties.ai().agent().maxNodesPerTask());
            if (!error.isEmpty()) {
                throw new IllegalStateException("画布动作未通过校验：" + error);
            }
            List<BoardAction> placed = actionApplier.layout(task.getBoardId(), actions);
            ApplyReport report = actionApplier.apply(task.getBoardId(), placed);
            if (report.skipped() > 0) {
                throw new IllegalStateException("有 " + report.skipped() + " 个动作引用了不存在或不属于画布的节点");
            }
            if (report.changedShapes() == 0) {
                throw new IllegalStateException("本步没有产生任何画布变化");
            }
            step.done(describe(report));
            stepRepository.save(step);
            updateTaskProgress(task.getId(), planProgress, "步骤 " + step.getSeq() + " 完成：" + step.getTitle());
            return report;
        });
    }

    /** 清空步骤：三张表同一事务删除，资产对象延后到计划成功后清理 */
    private void executeClear(AiTask task, AiTaskStep step, int planProgress, List<String> pendingPurgeKeys) {
        transactionTemplate.executeWithoutResult(status -> {
            cancellation.assertNotCancelled(task.getId());
            List<Shape> shapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(task.getBoardId());
            List<BoardEdge> edges = edgeRepository.findByBoardIdOrderByCreatedAtAsc(task.getBoardId());
            shapeRepository.deleteAll(shapes);
            edgeRepository.deleteAll(edges);
            snapshotRepository.deleteByBoardId(task.getBoardId());
            List<String> keys = assetService.deleteAllRecords(task.getBoardId(), task.getOwnerId());
            pendingPurgeKeys.addAll(keys);
            step.done("删除 " + shapes.size() + " 个节点、" + edges.size() + " 条连线、" + keys.size() + " 张图片");
            stepRepository.save(step);
            updateTaskProgress(task.getId(), planProgress, "步骤 " + step.getSeq() + " 完成：" + step.getTitle());
        });
        if (!shapeRepository.findByBoardIdOrderByCreatedAtAsc(task.getBoardId()).isEmpty()) {
            throw new IllegalStateException("清空后画布仍有节点");
        }
        if (!edgeRepository.findByBoardIdOrderByCreatedAtAsc(task.getBoardId()).isEmpty()) {
            throw new IllegalStateException("清空后画布仍有连线");
        }
        if (!assetRepository.findByBoardIdAndOwnerIdOrderByCreatedAtAsc(task.getBoardId(), task.getOwnerId())
                .isEmpty()) {
            throw new IllegalStateException("清空后画布仍有图片资产");
        }
    }

    /** 文生图步骤：生成图片并落资产记录，随后确认记录可查 */
    private void executeImage(AiTask task, AiTaskStep step, int planProgress, List<String> createdAssetKeys) {
        byte[] imageBytes = imageGenerationService.generate(step.getBrief());
        Asset asset = assetService.createImageAsset(task.getBoardId(), task.getOwnerId(), task.getId(), imageBytes);
        createdAssetKeys.add(asset.getStorageKey());
        transactionTemplate.executeWithoutResult(status -> {
            step.done("已生成 1 张图片");
            stepRepository.save(step);
            updateTaskProgress(task.getId(), planProgress, "步骤 " + step.getSeq() + " 完成：" + step.getTitle());
        });
        if (assetRepository.findById(asset.getId()).isEmpty()) {
            throw new IllegalStateException("图片资产未落库");
        }
    }

    /** 整理步骤：只调整已有节点坐标，节点数量必须保持不变 */
    private void executeLayout(AiTask task, AiTaskStep step, int planProgress) {
        int before = countShapes(task.getBoardId());
        transactionTemplate.executeWithoutResult(status -> {
            cancellation.assertNotCancelled(task.getId());
            actionApplier.layoutExistingBoard(task.getBoardId());
            step.done("已重新整理 " + before + " 个节点");
            stepRepository.save(step);
            updateTaskProgress(task.getId(), planProgress, "步骤 " + step.getSeq() + " 完成：" + step.getTitle());
        });
        int after = countShapes(task.getBoardId());
        if (after != before) {
            throw new IllegalStateException("整理画布后节点数量变化：整理前 " + before + "，整理后 " + after);
        }
    }

    /** 计划成功：标记任务成功，再清理清空步骤留下的资产对象并删除检查点 */
    private void finishSucceeded(AiTask task, List<AiTaskStep> steps, List<String> pendingPurgeKeys) {
        transactionTemplate.executeWithoutResult(status -> {
            cancellation.assertNotCancelled(task.getId());
            AiTask current = taskRepository.findById(task.getId()).orElseThrow();
            current.markSucceeded("{\"mode\":\"plan\",\"stepCount\":" + steps.size() + "}");
            taskRepository.save(current);
        });
        log.info("计划执行成功：taskId={} 步骤数={}", task.getId(), steps.size());
        eventPublisher.publishQuietly(task.getBoardId(), task.getId(),
                TaskEvent.done(task.getId(), task.getBoardId(), AiTaskType.AGENT_PLAN.name()));
        assetService.purgeObjectsNow(pendingPurgeKeys);
        deleteCheckpointQuietly(task.getId());
    }

    /** 计划失败：先回退画布，再落步骤与任务终态，最后推送事件 */
    private void handleFailed(AiTask task, AiTaskStep active, Exception exception, List<String> createdAssetKeys) {
        String errorMessage = truncate(message(exception));
        String restoreError = restoreQuietly(task);
        boolean restored = restoreError == null;
        String taskError = restored ? errorMessage
                : "画布回退失败：" + restoreError + "；原始错误：" + errorMessage;
        List<AiTaskStep> touched = finishSteps(task, active, AiTaskStepStatus.FAILED, errorMessage);
        transactionTemplate.executeWithoutResult(status -> {
            AiTask current = taskRepository.findById(task.getId()).orElseThrow();
            current.markFailed(taskError);
            taskRepository.save(current);
        });
        log.error("计划执行失败：taskId={} 回退={} 错误={}", task.getId(), restored ? "成功" : "失败", errorMessage,
                exception);
        touched.forEach(step -> publishStep(task, step));
        eventPublisher.publishQuietly(task.getBoardId(), task.getId(),
                TaskEvent.failed(task.getId(), task.getBoardId(), taskError));
        if (restored) {
            assetService.purgeObjectsNow(createdAssetKeys);
        }
        deleteCheckpointQuietly(task.getId());
    }

    /** 计划被中断：回退后当前步骤 ABORTED、剩余步骤 SKIPPED */
    private void handleAborted(AiTask task, AiTaskStep active, List<String> createdAssetKeys) {
        String restoreError = restoreQuietly(task);
        boolean restored = restoreError == null;
        String message = restored ? "任务已中断，画布已恢复到计划开始前"
                : "任务已中断，但画布回退失败：" + restoreError;
        List<AiTaskStep> touched = finishSteps(task, active, AiTaskStepStatus.ABORTED, message);
        transactionTemplate.executeWithoutResult(status -> {
            AiTask current = taskRepository.findById(task.getId()).orElseThrow();
            current.markAborted();
            taskRepository.save(current);
        });
        log.warn("计划执行被中断：taskId={} 回退={}", task.getId(), restored ? "成功" : "失败");
        touched.forEach(step -> publishStep(task, step));
        eventPublisher.publishQuietly(task.getBoardId(), task.getId(),
                TaskEvent.aborted(task.getId(), task.getBoardId()));
        if (restored) {
            assetService.purgeObjectsNow(createdAssetKeys);
        }
        deleteCheckpointQuietly(task.getId());
    }

    /** 检查点保存失败：画布未动，不需要回退，所有步骤标记未执行 */
    private void finishWithoutCheckpoint(AiTask task, String errorMessage) {
        List<AiTaskStep> touched = finishSteps(task, null, AiTaskStepStatus.FAILED, errorMessage);
        transactionTemplate.executeWithoutResult(status -> {
            AiTask current = taskRepository.findById(task.getId()).orElseThrow();
            current.markFailed(errorMessage);
            taskRepository.save(current);
        });
        touched.forEach(step -> publishStep(task, step));
        eventPublisher.publishQuietly(task.getBoardId(), task.getId(),
                TaskEvent.failed(task.getId(), task.getBoardId(), errorMessage));
    }

    /**
     * 落步骤终态：当前步骤 FAILED/ABORTED，其后仍未开始的步骤 SKIPPED。
     * 返回本次变更的步骤，供提交后推送事件。
     */
    private List<AiTaskStep> finishSteps(AiTask task, AiTaskStep active, AiTaskStepStatus activeStatus,
            String message) {
        List<AiTaskStep> touched = new ArrayList<>();
        transactionTemplate.executeWithoutResult(status -> {
            List<AiTaskStep> rows = stepRepository.findByTaskIdOrderBySeqAsc(task.getId());
            for (AiTaskStep row : rows) {
                if (active != null && row.getId().equals(active.getId())) {
                    if (activeStatus == AiTaskStepStatus.FAILED) {
                        row.fail(message);
                    } else {
                        row.abort(message);
                    }
                    touched.add(stepRepository.save(row));
                } else if ((active == null || row.getSeq() > active.getSeq())
                        && row.getStatus() == AiTaskStepStatus.PENDING) {
                    row.skip();
                    touched.add(stepRepository.save(row));
                }
            }
        });
        return touched;
    }

    /** 开始步骤：更新步骤状态与任务进度，提交后推送步骤事件 */
    private void startStep(AiTask task, AiTaskStep step, int index, int total) {
        step.start("正在执行");
        transactionTemplate.executeWithoutResult(status -> {
            stepRepository.save(step);
            updateTaskProgress(task.getId(), index * 100 / total,
                    "步骤 " + (index + 1) + "/" + total + "：" + step.getTitle());
        });
        publishStep(task, step);
    }

    /** 步骤内百分比换算为计划级进度；进度上报失败不影响执行 */
    private void reportProgress(AiTask task, int index, int total, int percent, String message) {
        int planProgress = Math.min(99, (index * 100 + Math.max(0, Math.min(percent, 100))) / total);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                AiTask current = taskRepository.findById(task.getId()).orElseThrow();
                current.updateProgress(planProgress, message);
                taskRepository.save(current);
            });
        } catch (RuntimeException exception) {
            log.warn("更新任务进度失败：taskId={} error={}", task.getId(), exception.getMessage());
        }
        eventPublisher.publishQuietly(task.getBoardId(), task.getId(),
                TaskEvent.progress(task.getId(), task.getBoardId(), planProgress, message));
    }

    /** 事务内更新任务进度（同时刷新心跳锁） */
    private void updateTaskProgress(UUID taskId, int progress, String message) {
        AiTask current = taskRepository.findById(taskId).orElseThrow();
        current.updateProgress(progress, message);
        taskRepository.save(current);
    }

    private void publishStep(AiTask task, AiTaskStep step) {
        eventPublisher.publishQuietly(task.getBoardId(), task.getId(),
                TaskEvent.step(task.getId(), task.getBoardId(), step.getId(), step.getSeq(),
                        step.getStepType() == null ? null : step.getStepType().name(), step.getTitle(),
                        step.getStatus().name(), step.getProgress(), step.getMessage()));
    }

    /** 恢复画布；成功返回 null，失败返回原因 */
    private String restoreQuietly(AiTask task) {
        try {
            checkpointService.restore(task);
            return null;
        } catch (RuntimeException exception) {
            log.error("画布回退失败：taskId={} boardId={} error={}", task.getId(), task.getBoardId(),
                    exception.getMessage(), exception);
            return message(exception);
        }
    }

    private void deleteCheckpointQuietly(UUID taskId) {
        try {
            checkpointService.delete(taskId);
        } catch (RuntimeException exception) {
            log.warn("删除画布检查点失败：taskId={} error={}", taskId, exception.getMessage());
        }
    }

    private void recordApplyStepQuietly(UUID runId, AiTaskStep step) {
        try {
            recorder.recordStep(runId, AgentStepType.APPLY, null,
                    Map.of("stepType", step.getStepType().name(), "seq", step.getSeq()),
                    step.getResultMessage(), 0, null);
        } catch (RuntimeException exception) {
            log.warn("记录 Agent 应用步骤失败：runId={} error={}", runId, exception.getMessage());
        }
    }

    private Usage usage(TaskRuntime runtime) {
        return new Usage(runtime.modelCalls(), runtime.toolCalls(), runtime.promptTokens(),
                runtime.completionTokens());
    }

    /** 按步骤记录用量差值，避免同一计划的多个步骤重复累计 */
    private void recordUsageQuietly(UUID runId, TaskRuntime runtime, Usage before) {
        int modelCalls = runtime.modelCalls() - before.modelCalls;
        int toolCalls = runtime.toolCalls() - before.toolCalls;
        int promptTokens = runtime.promptTokens() - before.promptTokens;
        int completionTokens = runtime.completionTokens() - before.completionTokens;
        if (modelCalls == 0 && toolCalls == 0 && promptTokens == 0 && completionTokens == 0) {
            return;
        }
        try {
            recorder.addUsage(runId, modelCalls, toolCalls, promptTokens, completionTokens, runtime.lastModel());
        } catch (RuntimeException exception) {
            log.warn("写入 Agent 用量失败：runId={} error={}", runId, exception.getMessage());
        }
    }

    private void finishRunQuietly(UUID runId, AgentRunStatus status, String errorMessage) {
        try {
            recorder.finishRun(runId, status, null, errorMessage);
        } catch (RuntimeException exception) {
            log.warn("结束 Agent 运行记录失败：runId={} status={} error={}", runId, status,
                    exception.getMessage());
        }
    }

    private int countShapes(UUID boardId) {
        return shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId).size();
    }

    private int countEdges(UUID boardId) {
        return edgeRepository.findByBoardIdOrderByCreatedAtAsc(boardId).size();
    }

    private String describe(ApplyReport report) {
        List<String> parts = new ArrayList<>();
        if (report.createdShapes() > 0) {
            parts.add("新增 " + report.createdShapes() + " 个节点");
        }
        if (report.createdEdges() > 0) {
            parts.add("新增 " + report.createdEdges() + " 条连线");
        }
        if (report.updatedShapes() > 0) {
            parts.add("更新 " + report.updatedShapes() + " 个节点");
        }
        if (report.deletedShapes() > 0) {
            parts.add("删除 " + report.deletedShapes() + " 个节点");
        }
        return String.join("、", parts);
    }

    private String message(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    /** error_message 列长度 */
    private String truncate(String value) {
        return value == null || value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    /** 单个步骤开始前的累计用量快照 */
    private record Usage(int modelCalls, int toolCalls, int promptTokens, int completionTokens) {
    }
}
