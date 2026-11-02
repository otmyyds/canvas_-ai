package com.picturecreate.doccanvas.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.picturecreate.doccanvas.api.dto.AiTaskDtos.AiTaskResponse;
import com.picturecreate.doccanvas.api.dto.TaskEvent;
import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.domain.AgentRole;
import com.picturecreate.doccanvas.domain.AgentRun;
import com.picturecreate.doccanvas.domain.AgentRunStatus;
import com.picturecreate.doccanvas.domain.AiTask;
import com.picturecreate.doccanvas.domain.AiTaskStatus;
import com.picturecreate.doccanvas.domain.AiTaskStep;
import com.picturecreate.doccanvas.domain.AiTaskType;
import com.picturecreate.doccanvas.domain.UserAccount;
import com.picturecreate.doccanvas.repository.AgentRunRepository;
import com.picturecreate.doccanvas.repository.AiTaskRepository;
import com.picturecreate.doccanvas.repository.AiTaskStepRepository;
import com.picturecreate.doccanvas.repository.UserAccountRepository;
import com.picturecreate.doccanvas.service.ai.agent.AgentPlanExecutor;
import com.picturecreate.doccanvas.service.ai.agent.AgentRunRecorder;
import com.picturecreate.doccanvas.service.ai.agent.PlanDraft;
import com.picturecreate.doccanvas.service.ai.llm.TaskAbortedException;
import com.picturecreate.doccanvas.service.async.AiTaskStreamProducer;

/**
 * AI 任务编排服务：负责计划任务的创建、认领与回收，执行本身交给 AgentPlanExecutor。
 *
 * 任务状态机：CREATED → QUEUED → RUNNING → SUCCEEDED / FAILED / ABORTED
 *
 * 一个计划任务包含 N 条有序步骤；执行器逐步执行，每一步结束后检查真实画布状态，
 * 任一步失败或任务被中断时回退到计划开始前的检查点。
 *
 * 本类的事务规则（务必遵守）：
 * 1. 任务由 Redis Stream 消费者线程拉起，该线程上没有任何事务包裹，
 *    每一个数据库状态变更都必须自己保证原子性。
 * 2. 类内自调用不走 Spring 代理，@Transactional 不会生效；需要事务时显式用 transactionTemplate。
 * 3. 外部 I/O（对象存储、Redis、WebSocket 推送、Stream 投递）一律放在事务提交之后。
 */
@Service
public class AiTaskService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiTaskService.class);

    /** 序列化/反序列化任务上下文 JSON（选中元素 id 列表） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 活跃状态集合（占用并发配额的任务状态） */
    private static final List<AiTaskStatus> ACTIVE_STATUSES = List.of(AiTaskStatus.QUEUED, AiTaskStatus.RUNNING,
            AiTaskStatus.ABORTING);

    private final BoardService boardService;
    private final AiTaskRepository taskRepository;
    private final AiTaskStepRepository stepRepository;
    private final AgentRunRepository agentRunRepository;
    private final QuotaService quotaService;
    private final UserAccountRepository userAccountRepository;
    private final TaskEventPublisher eventPublisher;
    private final AiTaskStreamProducer aiTaskStreamProducer;
    private final AppProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final AgentRunRecorder agentRunRecorder;
    private final AgentPlanExecutor planExecutor;
    private final AiTaskCancellation cancellation;
    private final BoardCheckpointService checkpointService;

    public AiTaskService(BoardService boardService, AiTaskRepository taskRepository,
            AiTaskStepRepository stepRepository, AgentRunRepository agentRunRepository,
            QuotaService quotaService, UserAccountRepository userAccountRepository,
            TaskEventPublisher eventPublisher, AiTaskStreamProducer aiTaskStreamProducer,
            AppProperties properties, TransactionTemplate transactionTemplate, AgentRunRecorder agentRunRecorder,
            AgentPlanExecutor planExecutor, AiTaskCancellation cancellation,
            BoardCheckpointService checkpointService) {
        this.boardService = boardService;
        this.taskRepository = taskRepository;
        this.stepRepository = stepRepository;
        this.agentRunRepository = agentRunRepository;
        this.quotaService = quotaService;
        this.userAccountRepository = userAccountRepository;
        this.eventPublisher = eventPublisher;
        this.aiTaskStreamProducer = aiTaskStreamProducer;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.agentRunRecorder = agentRunRecorder;
        this.planExecutor = planExecutor;
        this.cancellation = cancellation;
        this.checkpointService = checkpointService;
    }

    /**
     * 创建计划任务：一个 AGENT_PLAN 任务加 N 条有序步骤。
     * 事务提交后（afterCommit）投递到 Stream，避免长任务阻塞 HTTP 请求。
     *
     * @param selectedShapeIds 用户选中的图形节点 id（编辑类步骤的操作目标）
     * @param sessionId        来源会话 id，用于执行完成后写入任务摘要
     */
    @Transactional
    public AiTaskResponse createPlan(UUID boardId, CurrentUser user, String goal, List<PlanDraft> steps,
            List<String> selectedShapeIds, UUID sessionId) {
        boardService.getOwned(boardId, user);
        enforceConcurrency(boardId, user);
        // 与任务创建同一事务：超限抛 429，回滚本次任务名额
        quotaService.reserveTask(user);
        AiTask task = new AiTask(boardId, user.id(), AiTaskType.AGENT_PLAN, goal);
        task.setContextJson(contextJson(selectedShapeIds, sessionId));
        AiTask saved = taskRepository.save(task);
        for (int index = 0; index < steps.size(); index++) {
            PlanDraft draft = steps.get(index);
            AiTaskType stepType = AiTaskType.valueOf(draft.type().trim().toUpperCase(Locale.ROOT));
            stepRepository.save(new AiTaskStep(saved.getId(), index + 1, stepType, draft.title(),
                    draft.description(), draft.brief()));
        }
        log.info("AI 计划任务已创建：taskId={} boardId={} 步骤数={} 目标={} 选中图形={}", saved.getId(), boardId,
                steps.size(), goal, selectedShapeIds == null ? 0 : selectedShapeIds.size());
        // 事件推送与异步调度都放在事务提交之后，订阅方与消费者必须先看到已提交的任务行
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishQuietly(boardId, saved.getId(),
                        TaskEvent.created(saved.getId(), boardId, AiTaskType.AGENT_PLAN.name()));
                aiTaskStreamProducer.send(saved.getId());
            }
        });
        return get(saved.getId(), user);
    }

    /** 查询画布下所有任务（含步骤），按创建时间倒序 */
    @Transactional(readOnly = true)
    public List<AiTaskResponse> list(UUID boardId, CurrentUser user) {
        boardService.getOwned(boardId, user);
        return taskRepository.findByBoardIdAndOwnerIdOrderByCreatedAtDesc(boardId, user.id()).stream()
                .map(task -> response(task, user))
                .toList();
    }

    /** 查询单个任务详情（含步骤） */
    @Transactional(readOnly = true)
    public AiTaskResponse get(UUID taskId, CurrentUser user) {
        AiTask task = taskRepository.findByIdAndOwnerId(taskId, user.id())
                .orElseThrow(() -> new NotFoundException("AI 任务不存在"));
        return response(task, user);
    }

    /**
     * 中断任务：设置 DB abort_requested 标记 + Redis cancel key。
     * 执行器在每次模型/工具调用前检查并停止，随后回退画布。
     */
    @Transactional
    public AiTaskResponse abort(UUID taskId, CurrentUser user) {
        AiTask task = taskRepository.findByIdAndOwnerId(taskId, user.id())
                .orElseThrow(() -> new NotFoundException("AI 任务不存在"));
        task.requestAbort();
        // 取消标记保留在事务内：宁可多设不可漏设，回滚后残留一个标记只会让任务提前停下
        cancellation.markCancelled(taskId);
        UUID boardId = task.getBoardId();
        int progress = task.getProgress();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishQuietly(boardId, taskId, TaskEvent.progress(taskId, boardId, progress,
                        "已请求中断，任务会在下一个安全检查点停止"));
            }
        });
        return response(task, user);
    }

    /**
     * 由 Stream 消费者触发执行任务。
     * 幂等守卫：终态任务跳过；只有 QUEUED 能被原子认领，避免重复消息重复执行。
     */
    public void executeTask(UUID taskId) {
        AiTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("任务不存在，跳过执行：taskId={}", taskId);
            return;
        }
        if (isTerminal(task.getStatus())) {
            log.info("任务已处于终态，跳过执行：taskId={} status={}", taskId, task.getStatus());
            return;
        }
        if (task.getStatus() != AiTaskStatus.QUEUED) {
            log.info("任务当前状态不可认领，跳过执行：taskId={} status={}", taskId, task.getStatus());
            return;
        }
        Instant now = Instant.now();
        String workerId = "redis-stream-" + Thread.currentThread().getName();
        int claimed = taskRepository.claimQueued(taskId, AiTaskStatus.QUEUED, AiTaskStatus.RUNNING, workerId,
                now, now.plusSeconds(90), "启动任务");
        if (claimed == 0) {
            log.info("任务已被其他消费者认领或状态已变化，跳过执行：taskId={}", taskId);
            return;
        }
        runTask(taskId);
    }

    private boolean isTerminal(AiTaskStatus status) {
        return status == AiTaskStatus.SUCCEEDED || status == AiTaskStatus.FAILED || status == AiTaskStatus.ABORTED;
    }

    /** 认领成功后进入计划执行；执行器内部已完成回退与步骤收尾 */
    private void runTask(UUID taskId) {
        try {
            // 排队期间可能已被用户中断：启动前再检查一次
            cancellation.assertNotCancelled(taskId);
            AiTask task = taskRepository.findById(taskId).orElseThrow();
            begin(task);
            log.info("任务开始执行：taskId={} type={} boardId={}", taskId, task.getType(), task.getBoardId());
            if (task.getType() != AiTaskType.AGENT_PLAN) {
                throw new IllegalStateException("未知任务类型：" + task.getType());
            }
            planExecutor.execute(task, ownerUser(task.getOwnerId()),
                    parseStringList(task.getContextJson(), "selectedShapeIds"),
                    parseUuid(task.getContextJson(), "sessionId"));
        } catch (TaskAbortedException exception) {
            // 执行器启动前的取消：此时还没有检查点，画布未动
            finishRunningRunsQuietly(taskId, AgentRunStatus.ABORTED, "任务被用户中断");
            markAborted(taskId);
        } catch (Exception exception) {
            finishRunningRunsQuietly(taskId, AgentRunStatus.FAILED, exception.getMessage());
            markFailed(taskId, exception);
        }
    }

    /** 任务启动：状态已由原子认领更新为 RUNNING，这里只推送进度事件 */
    private void begin(AiTask task) {
        log.info("任务开始执行（已认领）：taskId={} status={}", task.getId(), task.getStatus());
        eventPublisher.publishQuietly(task.getBoardId(), task.getId(),
                TaskEvent.progress(task.getId(), task.getBoardId(), 3, "任务已启动，Agent 正在准备"));
    }

    /** 结束运行记录；审计写入失败只记日志，不反向覆盖任务状态 */
    private void finishRunningRunsQuietly(UUID taskId, AgentRunStatus status, String errorMessage) {
        try {
            agentRunRecorder.finishRunningRuns(taskId, status, errorMessage);
        } catch (Exception exception) {
            log.warn("关闭运行记录失败：taskId={} status={} error={}", taskId, status, exception.getMessage());
        }
    }

    /** 任务中断收尾：标记 ABORTED，推送 aborted 事件 */
    private void markAborted(UUID taskId) {
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        task.markAborted();
        taskRepository.save(task);
        log.info("任务已中断：taskId={}", taskId);
        eventPublisher.publishQuietly(task.getBoardId(), taskId, TaskEvent.aborted(taskId, task.getBoardId()));
    }

    /**
     * 应用启动时回收遗留的活跃任务：进程重启后无人再调度这些任务，
     * 有检查点的计划先回退画布，再统一落成 FAILED，释放并发额度。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reapStaleTasksOnStartup() {
        List<AiTask> stale = taskRepository.findByStatusIn(ACTIVE_STATUSES);
        if (stale.isEmpty()) {
            return;
        }
        log.info("启动回收遗留活跃任务：{} 个", stale.size());
        for (AiTask task : stale) {
            try {
                restoreForReaper(task);
                transactionTemplate.executeWithoutResult(status -> {
                    AiTask current = taskRepository.findById(task.getId()).orElseThrow();
                    current.markFailed("服务重启，任务已终止");
                    taskRepository.save(current);
                });
                agentRunRecorder.finishRunningRuns(task.getId(), AgentRunStatus.FAILED, "服务重启，任务已终止");
                eventPublisher.publishQuietly(task.getBoardId(), task.getId(),
                        TaskEvent.failed(task.getId(), task.getBoardId(), "服务重启，任务已终止"));
                deleteCheckpointQuietly(task.getId());
            } catch (Exception exception) {
                log.warn("启动回收任务失败 taskId={} error={}", task.getId(), exception.getMessage());
            }
        }
    }

    /**
     * 定时回收卡在 ABORTING 却无法自行收敛的任务。
     * 锁仍在有效期内的任务说明 worker 还活着并在刷新心跳，跳过，避免抢先覆盖终态。
     */
    @Scheduled(fixedDelay = 5000)
    public void reapAbortingTasks() {
        List<AiTask> aborting = taskRepository.findByStatus(AiTaskStatus.ABORTING);
        if (aborting.isEmpty()) {
            return;
        }
        log.info("回收僵尸中断任务：{} 个", aborting.size());
        Instant now = Instant.now();
        for (AiTask task : aborting) {
            if (task.getLockExpiresAt() != null && task.getLockExpiresAt().isAfter(now)) {
                continue;
            }
            try {
                restoreForReaper(task);
                markAborted(task.getId());
                agentRunRecorder.finishRunningRuns(task.getId(), AgentRunStatus.ABORTED,
                        "任务长时间处于中断状态，已回收");
                deleteCheckpointQuietly(task.getId());
            } catch (Exception exception) {
                log.warn("回收中断任务失败 taskId={} error={}", task.getId(), exception.getMessage());
            }
        }
    }

    /** 删除检查点；审计数据清理失败只记日志，不影响任务终态 */
    private void deleteCheckpointQuietly(UUID taskId) {
        try {
            checkpointService.delete(taskId);
        } catch (RuntimeException exception) {
            log.warn("删除画布检查点失败：taskId={} error={}", taskId, exception.getMessage());
        }
    }

    /** 回收器回退画布：恢复失败只记日志，任务仍按终态收尾 */
    private void restoreForReaper(AiTask task) {
        if (task.getType() != AiTaskType.AGENT_PLAN) {
            return;
        }
        try {
            checkpointService.restore(task);
        } catch (RuntimeException exception) {
            log.warn("回收任务回退画布失败：taskId={} error={}", task.getId(), exception.getMessage());
        }
    }

    /** 任务失败收尾：标记 FAILED（记录错误信息），推送 failed 事件 */
    private void markFailed(UUID taskId, Exception exception) {
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        task.markFailed(exception.getMessage());
        taskRepository.save(task);
        log.error("任务失败：taskId={} error={}", taskId, exception.getMessage(), exception);
        eventPublisher.publishQuietly(task.getBoardId(), taskId, TaskEvent.failed(taskId, task.getBoardId(),
                exception.getMessage() == null ? "任务失败" : exception.getMessage()));
    }

    /** 任务 owner 转为工具可用的当前用户 */
    private CurrentUser ownerUser(UUID ownerId) {
        UserAccount user = userAccountRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalStateException("任务归属用户不存在：" + ownerId));
        return new CurrentUser(user.getId(), user.getEmail(), user.getDisplayName());
    }

    /** 把选中元素与会话 id 序列化为 JSON，存入任务 context_json */
    private String contextJson(List<String> shapeIds, UUID sessionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("selectedShapeIds", shapeIds == null ? List.of() : shapeIds);
        if (sessionId != null) {
            payload.put("sessionId", sessionId.toString());
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception exception) {
            log.warn("序列化任务上下文失败：{}", exception.getMessage());
            return null;
        }
    }

    /** 从 context_json 解析 UUID；缺失或非法返回 null（无会话的任务不写会话摘要） */
    private UUID parseUuid(String contextJson, String key) {
        if (contextJson == null || contextJson.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> root = OBJECT_MAPPER.readValue(contextJson, Map.class);
            Object value = root.get(key);
            return value == null ? null : UUID.fromString(String.valueOf(value));
        } catch (Exception exception) {
            log.warn("解析任务上下文 UUID 失败：key={} error={}", key, exception.getMessage());
            return null;
        }
    }

    /** 从 context_json 解析指定 key 的字符串列表，缺失/非法/为空返回空列表 */
    @SuppressWarnings("unchecked")
    private List<String> parseStringList(String contextJson, String key) {
        if (contextJson == null || contextJson.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> root = OBJECT_MAPPER.readValue(contextJson, Map.class);
            Object value = root.get(key);
            if (!(value instanceof List<?> list)) {
                return List.of();
            }
            return list.stream().map(String::valueOf).toList();
        } catch (Exception exception) {
            log.warn("解析任务上下文 JSON 失败：{}", exception.getMessage());
            return List.of();
        }
    }

    /** 任务响应：步骤 + 按运行记录汇总的成本信息 */
    private AiTaskResponse response(AiTask task, CurrentUser user) {
        List<AiTaskStep> steps = stepRepository.findByTaskIdOrderBySeqAsc(task.getId());
        long modelCalls = 0;
        long toolCalls = 0;
        long totalTokens = 0;
        long branchCount = 0;
        for (AgentRun run : agentRunRepository.findByTaskIdAndOwnerId(task.getId(), user.id())) {
            if (run.getRole() == AgentRole.BRANCH_WORKER) {
                branchCount++;
            }
            // 分支与审查运行会把用量同时记到父运行，只汇总顶层运行避免重复计数
            if (run.getParentRunId() == null) {
                modelCalls += run.getModelCalls();
                toolCalls += run.getToolCalls();
                totalTokens += run.getTotalTokens();
            }
        }
        return AiTaskResponse.from(task, steps, modelCalls, toolCalls, totalTokens, branchCount);
    }

    /** 并发控制：每用户最多 N 个活跃任务、每画布最多 M 个写入型任务 */
    private void enforceConcurrency(UUID boardId, CurrentUser user) {
        AppProperties.Task taskConfig = properties.ai().task();
        long runningForUser = taskRepository.countByOwnerIdAndStatusIn(user.id(), ACTIVE_STATUSES);
        if (runningForUser >= taskConfig.perUserConcurrency()) {
            throw new IllegalStateException("当前用户正在执行的 AI 任务过多");
        }
        long runningForBoard = taskRepository.countByBoardIdAndStatusIn(boardId, ACTIVE_STATUSES);
        if (runningForBoard >= taskConfig.perBoardWriteConcurrency()) {
            throw new IllegalStateException("当前画布已有写入型 AI 任务正在执行");
        }
    }
}
