package com.picturecreate.doccanvas.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.picturecreate.doccanvas.api.dto.AiTaskDtos.AiTaskResponse;
import com.picturecreate.doccanvas.api.dto.TaskEvent;
import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.domain.AiTask;
import com.picturecreate.doccanvas.domain.AiTaskStatus;
import com.picturecreate.doccanvas.domain.AiTaskStep;
import com.picturecreate.doccanvas.domain.AiTaskType;
import com.picturecreate.doccanvas.domain.BoardEdge;
import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.repository.AiTaskRepository;
import com.picturecreate.doccanvas.repository.AiTaskStepRepository;
import com.picturecreate.doccanvas.repository.BoardEdgeRepository;
import com.picturecreate.doccanvas.repository.BoardSnapshotRepository;
import com.picturecreate.doccanvas.repository.ShapeRepository;
import com.picturecreate.doccanvas.service.ai.AiGenerationService;
import com.picturecreate.doccanvas.service.ai.ImageGenerationService;
import com.picturecreate.doccanvas.service.ai.AiTaskContext;
import com.picturecreate.doccanvas.service.ai.BoardAction;
import com.picturecreate.doccanvas.service.ai.BoardActionResult;
import com.picturecreate.doccanvas.service.ai.GraphLayoutService;
import com.picturecreate.doccanvas.service.ai.SkillDefinition;
import com.picturecreate.doccanvas.service.ai.SkillLoader;

/**
 * AI 任务编排服务：负责 AI 任务的完整生命周期。
 *
 * 任务状态机：CREATED → QUEUED → RUNNING → SUCCEEDED / FAILED / ABORTED
 *
 * 核心流程（create 被调用后）：
 * 1. create() 在事务内保存任务记录，注册 afterCommit 回调，异步触发 runTask()
 * 2. runTask() 按固定步骤执行：加载技能 → 收集上下文 → 规划操作 → 应用操作
 * 3. 规划操作阶段调用 aiGenerationService.generateBoardActions()（真正调用大模型）
 * 4. 应用操作阶段 applyActions() 把模型产出的 BoardAction 落库（建图形/连线）
 *
 * 安全与约束：
 * - 每用户/每画布有并发限制（enforceConcurrency）
 * - 每步开始前检查是否被中断（assertNotCancelled：DB abort 标记 + Redis cancel key）
 * - AI 只提案（BoardAction），真正落库由本服务统一校验后执行
 */
@Service
public class AiTaskService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiTaskService.class);

    /** 序列化/反序列化任务上下文 JSON（选中元素 id 列表） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 思维导图节点宽高估算（包围盒与偏移用，取根节点最大尺寸） */
    private static final double SHAPE_W = 200;
    private static final double SHAPE_H = 96;
    /** 思维导图新图偏移：首个导图原点边距 / 与已有内容的间距 */
    private static final double MINDMAP_MARGIN = 120;
    private static final double MINDMAP_GAP = 160;

    /** 活跃状态集合（占用并发配额的任务状态） */
    private static final List<AiTaskStatus> ACTIVE_STATUSES = List.of(AiTaskStatus.QUEUED, AiTaskStatus.RUNNING,
            AiTaskStatus.ABORTING);

    private final BoardService boardService;
    private final AiTaskRepository taskRepository;
    private final AiTaskStepRepository stepRepository;
    private final BoardEdgeRepository edgeRepository;
    private final ShapeRepository shapeRepository;
    private final BoardSnapshotRepository snapshotRepository;
    private final SkillLoader skillLoader;
    private final TaskEventPublisher eventPublisher;
    private final AiGenerationService aiGenerationService;
    private final Executor aiTaskExecutor;
    private final StringRedisTemplate redisTemplate;
    private final AppProperties properties;
    private final ImageGenerationService imageGenerationService;
    private final AssetService assetService;

    public AiTaskService(BoardService boardService, AiTaskRepository taskRepository, AiTaskStepRepository stepRepository,
            BoardEdgeRepository edgeRepository, ShapeRepository shapeRepository,
            BoardSnapshotRepository snapshotRepository, SkillLoader skillLoader,
            TaskEventPublisher eventPublisher, AiGenerationService aiGenerationService,
            ImageGenerationService imageGenerationService, AssetService assetService,
            @Qualifier("aiTaskExecutor") Executor aiTaskExecutor, StringRedisTemplate redisTemplate,
            AppProperties properties) {
        this.boardService = boardService;
        this.taskRepository = taskRepository;
        this.stepRepository = stepRepository;
        this.edgeRepository = edgeRepository;
        this.shapeRepository = shapeRepository;
        this.snapshotRepository = snapshotRepository;
        this.skillLoader = skillLoader;
        this.eventPublisher = eventPublisher;
        this.aiGenerationService = aiGenerationService;
        this.imageGenerationService = imageGenerationService;
        this.assetService = assetService;
        this.aiTaskExecutor = aiTaskExecutor;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 创建 AI 任务（POST /api/boards/{boardId}/ai-tasks 入口）。
     * 事务提交后（afterCommit）异步执行 runTask，避免长任务阻塞 HTTP 请求。
     *
     * @param boardId 画布 id
     * @param user 当前用户
     * @param type 任务类型（思维导图/流程图/总结等）
     * @param prompt 用户输入
     * @param selectedShapeIds 选中的图形节点 id（编辑类任务的目标）
     * @param remainingPlan 多步骤计划里本任务之后的剩余任务类型（空表示单步任务）
     * @return 创建后的任务详情（含步骤）
     */
    @Transactional
    public AiTaskResponse create(UUID boardId, CurrentUser user, AiTaskType type, String prompt,
            List<String> selectedShapeIds, List<AiTaskType> remainingPlan) {
        boardService.getOwned(boardId, user);
        enforceConcurrency(boardId, user);
        AiTask task = new AiTask(boardId, user.id(), type, prompt);
        task.setContextJson(contextJson(selectedShapeIds, remainingPlan));
        AiTask saved = taskRepository.save(task);
        log.info("AI 任务已创建：taskId={} boardId={} type={} prompt={} 选中图形={} 后续步骤={}", saved.getId(),
                boardId, type, prompt, selectedShapeIds == null ? 0 : selectedShapeIds.size(),
                remainingPlan == null ? 0 : remainingPlan.size());
        eventPublisher.publish(boardId, saved.getId(), TaskEvent.created(saved.getId(), boardId, type.name()));
        // 事务提交后再异步执行，保证任务记录已落库
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                aiTaskExecutor.execute(() -> runTask(saved.getId()));
            }
        });
        return get(saved.getId(), user);
    }

    /** 查询画布下所有任务（含步骤），按创建时间倒序 */
    @Transactional(readOnly = true)
    public List<AiTaskResponse> list(UUID boardId, CurrentUser user) {
        boardService.getOwned(boardId, user);
        return taskRepository.findByBoardIdAndOwnerIdOrderByCreatedAtDesc(boardId, user.id()).stream()
                .map(task -> AiTaskResponse.from(task, stepRepository.findByTaskIdOrderByCreatedAtAsc(task.getId())))
                .toList();
    }

    /** 查询单个任务详情（含步骤） */
    @Transactional(readOnly = true)
    public AiTaskResponse get(UUID taskId, CurrentUser user) {
        AiTask task = taskRepository.findByIdAndOwnerId(taskId, user.id())
                .orElseThrow(() -> new NotFoundException("AI 任务不存在"));
        return AiTaskResponse.from(task, stepRepository.findByTaskIdOrderByCreatedAtAsc(taskId));
    }

    /**
     * 中断任务：设置 DB abort_requested 标记 + Redis cancel key。
     * 任务在执行中的每个安全检查点（assertNotCancelled）会感知并停止。
     */
    @Transactional
    public AiTaskResponse abort(UUID taskId, CurrentUser user) {
        AiTask task = taskRepository.findByIdAndOwnerId(taskId, user.id())
                .orElseThrow(() -> new NotFoundException("AI 任务不存在"));
        task.requestAbort();
        redisTemplate.opsForValue().set(cancelKey(taskId), "1", Duration.ofHours(1));
        eventPublisher.publish(task.getBoardId(), task.getId(), TaskEvent.progress(task.getId(), task.getBoardId(),
                task.getProgress(), "已请求中断，任务会在下一个安全检查点停止"));
        return AiTaskResponse.from(task, stepRepository.findByTaskIdOrderByCreatedAtAsc(taskId));
    }

    /**
     * 异步执行任务的完整流程（固定 4 步）：
     * 加载技能 → 收集上下文 → 规划操作 → 应用操作。
     * 任一步抛 AbortTaskException → 标记中断；其他异常 → 标记失败。
     */
    private void runTask(UUID taskId) {
        try {
            // 排队期间可能已被用户中断：启动前再检查一次，避免把已 ABORTED 的任务复活成 RUNNING
            assertNotCancelled(taskId);
            begin(taskId);
            AiTask task = taskRepository.findById(taskId).orElseThrow();
            log.info("任务开始执行：taskId={} type={} boardId={}", taskId, task.getType(), task.getBoardId());
            // 按任务类型匹配 Skill（SKILL.md），决定该任务可用哪些工具
            SkillDefinition skill = skillLoader.findForTask(task.getType().name()).orElse(null);
            String skillStep = skill == null
                    ? "未找到匹配技能，按通用流程执行"
                    : "已加载技能[" + skill.name() + "]，工具范围：" + String.join("、", skill.allowedTools());
            runStep(taskId, "加载技能", 15, skillStep);
            runStep(taskId, "收集上下文", 35, "已收集画布图形节点、资料文件和用户提示");
            runStep(taskId, "规划操作", 65, "已生成结构化画布操作方案");
            // 真正调用大模型生成动作
            BoardActionResult result = generate(taskId, skill);
            runStep(taskId, "应用操作", 90, "已应用后端校验通过的画布操作");
            finish(taskId, result);
        } catch (AbortTaskException exception) {
            markAborted(taskId);
        } catch (Exception exception) {
            markFailed(taskId, exception);
        }
    }

    /** 任务启动：标记 RUNNING，进度 3% */
    @Transactional
    protected void begin(UUID taskId) {
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        task.markRunning("本地执行器");
        task.updateProgress(3, "启动任务");
        taskRepository.save(task);
        log.info("任务标记 RUNNING：taskId={}", taskId);
        eventPublisher.publish(task.getBoardId(), taskId, TaskEvent.progress(taskId, task.getBoardId(), 3,
                "任务已启动，正在准备执行"));
    }

    /**
     * 构建任务上下文并调用大模型生成画布动作。
     * 注入：画布现有图形节点（供 AI 参考与删除）、匹配到的 Skill。
     * 资料检索交给模型通过 search_document_chunks 工具按需执行，服务端不预取注入。
     *
     * @param taskId 任务 id
     * @param skill 匹配到的 Skill（可为 null）
     * @return 模型产出的动作列表
     */
    @Transactional
    protected BoardActionResult generate(UUID taskId, SkillDefinition skill) {
        assertNotCancelled(taskId);
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        // 清空画布：确定性删除全部元素，不依赖 LLM 生成动作
        if (task.getType() == AiTaskType.CLEAR_CANVAS) {
            return clearCanvas(task);
        }
        // 文生图：调用百炼文生图接口生成图片资产（不产生图形节点动作）
        if (task.getType() == AiTaskType.GENERATE_IMAGE) {
            return generateImage(task);
        }
        List<AiTaskContext.SourceContext> sources = List.of();
        // 画布现有图形节点（思维导图/流程图节点，供 AI 参考与删除）
        List<AiTaskContext.ShapeContext> shapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(task.getBoardId())
                .stream()
                .map(shape -> new AiTaskContext.ShapeContext(shape.getId().toString(), shape.getTitle(),
                        shape.getShape(), shape.getLevel()))
                .toList();
        // 选中的图形节点（编辑类任务的操作目标，按持久化的 id 列表查内容）
        List<AiTaskContext.ShapeContext> selectedShapes = selectedShapes(task.getBoardId(), task.getContextJson());
        log.info("构建上下文：taskId={} 资料数={} 图形数={} 选中图形={} skill={}", taskId,
                sources.size(), shapes.size(), selectedShapes.size(),
                skill == null ? "无" : skill.slug());
        return aiGenerationService.generateBoardActions(new AiTaskContext(task.getId(), task.getBoardId(),
                task.getOwnerId(), task.getType(), task.getPrompt(), sources, shapes, skill, selectedShapes));
    }

    /**
     * 文生图：调用百炼文生图接口生成图片，上传 RustFS 并落 assets 表。
     * 图片通过 assets 接口返回前端渲染，不产生 BoardAction。
     */
    private BoardActionResult generateImage(AiTask task) {
        byte[] imageBytes = imageGenerationService.generate(task.getPrompt());
        assetService.createImageAsset(task.getBoardId(), task.getOwnerId(), task.getId(), imageBytes);
        log.info("AI 生图完成：taskId={} boardId={} size={}", task.getId(), task.getBoardId(), imageBytes.length);
        return new BoardActionResult(List.of(),
                "{\"mode\":\"image\",\"size\":%d,\"message\":\"图片已生成\"}".formatted(imageBytes.length));
    }

    /**
     * 清空画布：删除全部图形节点、连线以及 tldraw 快照（确定性操作，不依赖 LLM）。
     * 画布渲染有两套数据源：shapes/edges 表（AI 生成）+ board_snapshots 快照（手动元素），
     * 必须同时清空，否则快照里的元素会在前端刷新后残留。
     * 供 CLEAR_CANVAS 任务调用，返回空动作，后续 finish 正常收尾并推送 done 事件。
     */
    private BoardActionResult clearCanvas(AiTask task) {
        UUID boardId = task.getBoardId();
        List<Shape> shapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
        List<BoardEdge> edges = edgeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
        shapeRepository.deleteAll(shapes);
        edgeRepository.deleteAll(edges);
        snapshotRepository.deleteByBoardId(boardId);
        log.info("清空画布：boardId={} 删除图形={} 连线={} 快照已清空", boardId, shapes.size(), edges.size());
        return new BoardActionResult(List.of(),
                "{\"mode\":\"clear-canvas\",\"actionCount\":0,\"message\":\"画布已清空\"}");
    }

    /**
     * 任务成功收尾：应用动作 → 标记 SUCCEEDED → 推送 actions/done 事件。
     */
    @Transactional
    protected void finish(UUID taskId, BoardActionResult result) {
        assertNotCancelled(taskId);
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        applyActions(task, result.actions());
        log.info("任务成功：taskId={} 动作数={}", taskId, result.actions() == null ? 0 : result.actions().size());
        task.markSucceeded(result.resultJson());
        taskRepository.save(task);
        eventPublisher.publish(task.getBoardId(), taskId,
                TaskEvent.actions(taskId, task.getBoardId(), result.actions(), task.getType().name()));
        eventPublisher.publish(task.getBoardId(), taskId, TaskEvent.done(taskId, task.getBoardId(), task.getType().name()));
        chainNext(task);
    }

    /**
     * 链式执行下一步：多步骤计划里，当前任务成功后自动创建并调度下一个任务。
     * 本方法在异步执行线程内、当前任务已标记 SUCCEEDED 之后调用；该线程无事务包裹，
     * 直接落库并调度（repository 自带事务，save 返回即已提交），无需 afterCommit。
     * 失败只记录日志，不影响当前任务已成功的结果。
     */
    private void chainNext(AiTask task) {
        List<AiTaskType> remaining = parsePlan(task.getContextJson());
        if (remaining.isEmpty()) {
            return;
        }
        try {
            AiTaskType nextType = remaining.get(0);
            List<AiTaskType> rest = remaining.subList(1, remaining.size());
            AiTask next = new AiTask(task.getBoardId(), task.getOwnerId(), nextType, task.getPrompt());
            next.setContextJson(contextJson(List.of(), rest));
            AiTask saved = taskRepository.save(next);
            log.info("链式创建下一步任务：taskId={} boardId={} type={}", saved.getId(), task.getBoardId(), nextType);
            eventPublisher.publish(task.getBoardId(), saved.getId(),
                    TaskEvent.created(saved.getId(), task.getBoardId(), nextType.name()));
            aiTaskExecutor.execute(() -> runTask(saved.getId()));
        } catch (Exception exception) {
            log.warn("链式创建下一步任务失败，跳过后续步骤：taskId={} error={}", task.getId(), exception.getMessage());
        }
    }

    /** 任务中断收尾：标记 ABORTED，推送 aborted 事件 */
    @Transactional
    protected void markAborted(UUID taskId) {
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        task.markAborted();
        taskRepository.save(task);
        log.info("任务已中断：taskId={}", taskId);
        eventPublisher.publish(task.getBoardId(), taskId, TaskEvent.aborted(taskId, task.getBoardId()));
    }

    /**
     * 应用启动时回收遗留的活跃任务（QUEUED/RUNNING/ABORTING）。
     * 任务状态持久化在数据库，而 runTask 的异步提交只存在于内存线程池；
     * 进程重启后这些任务无人再调度、无人收敛，会永久占用每用户/每画布的并发额度。
     * 启动时（尚无任何执行线程在跑）统一把它们落成 FAILED，释放额度。
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
                task.markFailed("服务重启，任务已终止");
                taskRepository.save(task);
                eventPublisher.publish(task.getBoardId(), task.getId(),
                        TaskEvent.failed(task.getId(), task.getBoardId(), "服务重启，任务已终止"));
            } catch (Exception exception) {
                log.warn("启动回收任务失败 taskId={} error={}", task.getId(), exception.getMessage());
            }
        }
    }

    /**
     * 定时回收卡在 ABORTING 却无法自行收敛的任务。
     * 中断只设置 ABORTING 标记，依赖执行线程在安全检查点停止；若任务卡在大模型调用
     * （同步阻塞无超时）或排队中根本没被调度，就会永远停在 ABORTING，一直占用
     * 每用户/每画布的活跃任务额度。此处由独立调度线程兜底，把 ABORTING 直接落成 ABORTED。
     */
    @Scheduled(fixedDelay = 5000)
    public void reapAbortingTasks() {
        List<AiTask> aborting = taskRepository.findByStatus(AiTaskStatus.ABORTING);
        if (aborting.isEmpty()) {
            return;
        }
        log.info("回收僵尸中断任务：{} 个", aborting.size());
        for (AiTask task : aborting) {
            try {
                markAborted(task.getId());
            } catch (Exception exception) {
                log.warn("回收中断任务失败 taskId={} error={}", task.getId(), exception.getMessage());
            }
        }
    }

    /** 任务失败收尾：标记 FAILED（记录错误信息），推送 failed 事件 */
    @Transactional
    protected void markFailed(UUID taskId, Exception exception) {
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        task.markFailed(exception.getMessage());
        taskRepository.save(task);
        log.error("任务失败：taskId={} error={}", taskId, exception.getMessage(), exception);
        eventPublisher.publish(task.getBoardId(), taskId, TaskEvent.failed(taskId, task.getBoardId(),
                exception.getMessage() == null ? "任务失败" : exception.getMessage()));
    }

    /** 执行一个步骤：开始 → 短暂停留 → 更新进度 → 完成（含中断检查） */
    private void runStep(UUID taskId, String title, int taskProgress, String message) {
        AiTaskStep step = startStep(taskId, title, message);
        sleepAndCheck(taskId, 600);
        updateStep(taskId, step.getId(), 60, message);
        sleepAndCheck(taskId, 600);
        completeStep(taskId, step.getId(), taskProgress, message);
    }

    /** 步骤开始：落库 + 推送 step(running) 事件 */
    @Transactional
    protected AiTaskStep startStep(UUID taskId, String title, String message) {
        assertNotCancelled(taskId);
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        AiTaskStep step = new AiTaskStep(taskId, title);
        step.start(message);
        step = stepRepository.save(step);
        task.updateProgress(task.getProgress(), title);
        taskRepository.save(task);
        log.info("步骤开始：taskId={} step={} message={}", taskId, title, message);
        eventPublisher.publish(task.getBoardId(), taskId,
                TaskEvent.step(taskId, task.getBoardId(), step.getId(), title, "running", 0, message));
        return step;
    }

    /** 步骤进行中更新进度：落库 + 推送 step(running) 事件 */
    @Transactional
    protected void updateStep(UUID taskId, UUID stepId, int progress, String message) {
        assertNotCancelled(taskId);
        AiTaskStep step = stepRepository.findById(stepId).orElseThrow();
        step.update(progress, message);
        stepRepository.save(step);
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        eventPublisher.publish(task.getBoardId(), taskId,
                TaskEvent.step(taskId, task.getBoardId(), stepId, step.getTitle(), "running", progress, message));
    }

    /** 步骤完成：标记 DONE + 更新任务进度 + 推送 done 事件 */
    @Transactional
    protected void completeStep(UUID taskId, UUID stepId, int taskProgress, String message) {
        assertNotCancelled(taskId);
        AiTaskStep step = stepRepository.findById(stepId).orElseThrow();
        step.done(message);
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        task.updateProgress(taskProgress, step.getTitle());
        stepRepository.save(step);
        taskRepository.save(task);
        log.info("步骤完成：taskId={} step={} progress={}", taskId, step.getTitle(), taskProgress);
        eventPublisher.publish(task.getBoardId(), taskId,
                TaskEvent.step(taskId, task.getBoardId(), stepId, step.getTitle(), "done", 100, message));
        eventPublisher.publish(task.getBoardId(), taskId,
                TaskEvent.progress(taskId, task.getBoardId(), taskProgress, message));
    }

    /**
     * 应用模型产出的所有动作到数据库。
     *
     * 为什么分两遍？
     * 结构图（思维导图/流程图）里 connect 会引用"本计划内新建的节点"（临时 id 如 root1/branch1）。
     * 若先建图形（第一遍），记录 临时id→真实UUID 映射；再连线/删（第二遍），
     * 就能让 connect 通过映射找到真实节点 id。
     *
     * 前置：maybeLayoutGraph 已为结构图节点重算坐标（避免重叠）。
     */
    private void applyActions(AiTask task, List<BoardAction> actions) {
        if (actions == null) {
            return;
        }
        log.info("应用动作：taskId={} 动作数={}", task.getId(), actions.size());
        // 结构图任务：先用布局算法为节点计算坐标，再统一落库
        List<BoardAction> placed = maybeLayoutGraph(task, actions);
        // 第一遍：创建所有图形节点，记录 临时id->真实id 映射
        Map<String, UUID> shapeIdMap = new HashMap<>();
        for (BoardAction action : placed) {
            if (action instanceof BoardAction.CreateShape createShape) {
                UUID created = applyCreateShape(task, createShape);
                if (created != null && createShape.id() != null && !createShape.id().isBlank()) {
                    shapeIdMap.put(createShape.id(), created);
                }
            }
        }
        // 第二遍：处理连线/删除（connect 通过 shapeIdMap 解析临时 id）
        for (BoardAction action : placed) {
            if (action == null) {
                continue;
            }
            switch (action) {
                case BoardAction.Connect connect -> applyConnect(task, connect, shapeIdMap);
                case BoardAction.DeleteShape deleteShape -> applyDeleteShape(task, deleteShape);
                case BoardAction.CreateShape ignored -> {
                    // 第一遍已创建
                }
            }
        }
    }

    /**
     * 创建图形节点（思维导图/流程图节点）并落库到 shapes 表。
     *
     * @return 新节点的真实 UUID（供 connect 映射）
     */
    private UUID applyCreateShape(AiTask task, BoardAction.CreateShape action) {
        log.info("applyCreateShape: id={} title={} level={} pos=({},{})", action.id(), action.title(), action.level(),
                action.x(), action.y());
        Shape shape = new Shape(task.getBoardId(),
                action.title() == null || action.title().isBlank() ? "节点" : action.title(),
                action.shape() == null || action.shape().isBlank() ? "ellipse" : action.shape(),
                action.level(),
                action.x(),
                action.y(),
                action.width() <= 0 ? 140 : action.width(),
                action.height() <= 0 ? 60 : action.height());
        return shapeRepository.save(shape).getId();
    }

    /**
     * 对结构图/编辑任务重算节点坐标，避免重叠。
     * - 树型任务（思维导图/总结/学习计划/产品需求）→ 递归径向树布局 + 偏移到已有内容右侧空白
     * - 流程图 → 分层布局（layoutFlowchart）
     * - 编辑扩展图形节点 → 对选中节点所在树整树重排
     */
    private List<BoardAction> maybeLayoutGraph(AiTask task, List<BoardAction> actions) {
        if (isTreeTask(task.getType())) {
            return layoutNewMindmap(task, actions);
        }
        if (task.getType() == AiTaskType.EDIT_ELEMENT) {
            return reflowEditedTree(task, actions);
        }
        if (task.getType() == AiTaskType.CANVAS_STRUCTURE) {
            layoutExistingBoard(task);
            return List.of();
        }
        if (task.getType() != AiTaskType.GENERATE_FLOWCHART) {
            return actions;
        }
        // 流程图：收集节点与边，分层布局
        List<String> nodeIds = new ArrayList<>();
        Map<String, double[]> sizes = new HashMap<>();
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.CreateShape shape && shape.id() != null) {
                nodeIds.add(shape.id());
                sizes.put(shape.id(), new double[] { shape.width(), shape.height() });
            }
        }
        List<String[]> edges = new ArrayList<>();
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.Connect connect && connect.fromShapeId() != null
                    && connect.toShapeId() != null) {
                edges.add(new String[] { connect.fromShapeId(), connect.toShapeId() });
            }
        }
        if (nodeIds.isEmpty()) {
            return actions;
        }
        Map<String, double[]> positions = GraphLayoutService.layoutFlowchart(nodeIds, edges, sizes);
        return rebuildWithPositions(actions, positions);
    }

    /** 树型任务（思维导图/总结/学习计划/产品需求）：产出同一套父→子树结构，统一按径向树布局 */
    private boolean isTreeTask(AiTaskType type) {
        return type == AiTaskType.GENERATE_MINDMAP
                || type == AiTaskType.DOCUMENT_SUMMARY
                || type == AiTaskType.STUDY_PLAN
                || type == AiTaskType.PRODUCT_REQUIREMENT;
    }

    /**
     * 整理画布：对画布所有图形节点按树重新布局（递归径向树布局），不生成/删除任何内容。
     * 多棵树（多张导图）按各自包围盒从左到右排列、不重叠。
     * 直接更新已有节点坐标落库，供前端重新渲染。
     */
    private void layoutExistingBoard(AiTask task) {
        List<Shape> shapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(task.getBoardId());
        if (shapes.isEmpty()) {
            return;
        }
        List<BoardEdge> edges = edgeRepository.findByBoardIdOrderByCreatedAtAsc(task.getBoardId());
        Map<String, Shape> shapeById = new HashMap<>();
        Map<String, double[]> sizes = new HashMap<>();
        Map<String, Set<String>> undirected = new HashMap<>();
        for (Shape shape : shapes) {
            String id = shape.getId().toString();
            shapeById.put(id, shape);
            sizes.put(id, new double[] { shape.getWidth(), shape.getHeight() });
            undirected.put(id, new HashSet<>());
        }
        for (BoardEdge edge : edges) {
            String from = edge.getFromShapeId().toString();
            String to = edge.getToShapeId().toString();
            if (undirected.containsKey(from) && undirected.containsKey(to)) {
                undirected.get(from).add(to);
                undirected.get(to).add(from);
            }
        }
        // BFS 找连通分量（每棵树一张导图）
        List<List<String>> components = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (Shape shape : shapes) {
            String start = shape.getId().toString();
            if (!visited.add(start)) {
                continue;
            }
            List<String> component = new ArrayList<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                component.add(current);
                for (String next : undirected.getOrDefault(current, Set.of())) {
                    if (visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
            components.add(component);
        }
        // 每个连通分量分别布局，整体从左到右偏移排列
        double cursorX = MINDMAP_MARGIN;
        for (List<String> component : components) {
            Set<String> nodeSet = new HashSet<>(component);
            List<String[]> treeEdges = new ArrayList<>();
            for (BoardEdge edge : edges) {
                String from = edge.getFromShapeId().toString();
                String to = edge.getToShapeId().toString();
                if (nodeSet.contains(from) && nodeSet.contains(to)) {
                    treeEdges.add(new String[] { from, to });
                }
            }
            // 树结构（边数 == 节点数-1）且存在层级>0 的节点 → 思维导图径向布局；否则 → 流程图分层布局
            boolean isMindmapTree = treeEdges.size() == component.size() - 1
                    && component.stream().anyMatch(id -> shapeById.get(id).getLevel() > 0);
            Map<String, double[]> positions = isMindmapTree
                    ? GraphLayoutService.layoutMindmapTree(component, treeEdges, sizes)
                    : GraphLayoutService.layoutFlowchart(component, treeEdges, sizes);
            double[] box = boundingBox(positions);
            double offsetX = cursorX - box[0];
            double offsetY = MINDMAP_MARGIN - box[1];
            for (Map.Entry<String, double[]> entry : positions.entrySet()) {
                Shape shape = shapeById.get(entry.getKey());
                if (shape != null) {
                    shape.update(entry.getValue()[0] + offsetX, entry.getValue()[1] + offsetY);
                    shapeRepository.save(shape);
                }
            }
            cursorX += (box[2] - box[0]) + MINDMAP_GAP;
        }
    }

    /**
     * 新建思维导图：递归布局后整体平移到画布空白处（避免叠加到旧图之上）。
     * 首个导图放到原点边距；已有内容时放到其包围盒右侧。
     */
    private List<BoardAction> layoutNewMindmap(AiTask task, List<BoardAction> actions) {
        List<String> nodeIds = new ArrayList<>();
        Map<String, double[]> sizes = new HashMap<>();
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.CreateShape shape && shape.id() != null) {
                nodeIds.add(shape.id());
                sizes.put(shape.id(), new double[] { shape.width(), shape.height() });
            }
        }
        List<String[]> edges = new ArrayList<>();
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.Connect connect && connect.fromShapeId() != null
                    && connect.toShapeId() != null) {
                edges.add(new String[] { connect.fromShapeId(), connect.toShapeId() });
            }
        }
        if (nodeIds.isEmpty()) {
            return actions;
        }
        Map<String, double[]> positions = GraphLayoutService.layoutMindmapTree(nodeIds, edges, sizes);
        double[] newBox = boundingBox(positions);
        double[] existing = existingBounds(task.getBoardId());
        double offsetX;
        double offsetY;
        if (existing == null) {
            offsetX = MINDMAP_MARGIN - newBox[0];
            offsetY = MINDMAP_MARGIN - newBox[1];
        } else {
            offsetX = existing[2] + MINDMAP_GAP - newBox[0];
            offsetY = existing[1] - newBox[1];
        }
        Map<String, double[]> shifted = new HashMap<>();
        positions.forEach((id, pos) -> shifted.put(id, new double[] { pos[0] + offsetX, pos[1] + offsetY }));
        return rebuildWithPositions(actions, shifted);
    }

    /**
     * 编辑扩展图形节点（加分支）：把选中节点所在的那棵思维导图树整树重排。
     * 从新边起点（选中节点）BFS 找到其连通分量（仅这一棵导图，其他导图不动），
     * 合并本次新节点后统一布局，已有节点更新坐标、新节点带新坐标落库。
     */
    private List<BoardAction> reflowEditedTree(AiTask task, List<BoardAction> actions) {
        List<BoardAction.CreateShape> newShapes = actions.stream()
                .filter(a -> a instanceof BoardAction.CreateShape)
                .map(a -> (BoardAction.CreateShape) a)
                .filter(s -> s.id() != null && !s.id().isBlank())
                .toList();
        if (newShapes.isEmpty()) {
            return actions;
        }
        List<String[]> newEdges = new ArrayList<>();
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.Connect connect && connect.fromShapeId() != null
                    && !connect.fromShapeId().isBlank() && connect.toShapeId() != null
                    && !connect.toShapeId().isBlank()) {
                newEdges.add(new String[] { connect.fromShapeId(), connect.toShapeId() });
            }
        }
        List<Shape> existingShapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(task.getBoardId());
        List<BoardEdge> existingEdges = edgeRepository.findByBoardIdOrderByCreatedAtAsc(task.getBoardId());
        Map<String, Shape> shapeById = new HashMap<>();
        Map<String, double[]> sizes = new HashMap<>();
        for (Shape shape : existingShapes) {
            shapeById.put(shape.getId().toString(), shape);
            sizes.put(shape.getId().toString(), new double[] { shape.getWidth(), shape.getHeight() });
        }
        // 无向邻接，用于从选中节点 BFS 找所在树
        Map<String, Set<String>> undirected = new HashMap<>();
        for (Shape shape : existingShapes) {
            undirected.put(shape.getId().toString(), new HashSet<>());
        }
        for (BoardEdge edge : existingEdges) {
            String from = edge.getFromShapeId().toString();
            String to = edge.getToShapeId().toString();
            if (undirected.containsKey(from) && undirected.containsKey(to)) {
                undirected.get(from).add(to);
                undirected.get(to).add(from);
            }
        }
        Set<String> treeNodes = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String[] edge : newEdges) {
            if (undirected.containsKey(edge[0]) && treeNodes.add(edge[0])) {
                queue.add(edge[0]);
            }
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : undirected.getOrDefault(current, Set.of())) {
                if (treeNodes.add(next)) {
                    queue.add(next);
                }
            }
        }
        if (treeNodes.isEmpty()) {
            return actions;
        }
        // 完整树：已有节点（真实 id）+ 新节点（临时 id），边 = 树内已有边 + 新边
        List<String> allNodes = new ArrayList<>(treeNodes);
        List<String[]> treeEdges = new ArrayList<>();
        for (BoardEdge edge : existingEdges) {
            String from = edge.getFromShapeId().toString();
            String to = edge.getToShapeId().toString();
            if (treeNodes.contains(from) && treeNodes.contains(to)) {
                treeEdges.add(new String[] { from, to });
            }
        }
        for (BoardAction.CreateShape shape : newShapes) {
            allNodes.add(shape.id());
            sizes.put(shape.id(), new double[] { shape.width(), shape.height() });
        }
        treeEdges.addAll(newEdges);
        Map<String, double[]> positions = GraphLayoutService.layoutMindmapTree(allNodes, treeEdges, sizes);
        // 更新已有节点坐标
        for (Map.Entry<String, double[]> entry : positions.entrySet()) {
            Shape shape = shapeById.get(entry.getKey());
            if (shape != null) {
                shape.update(entry.getValue()[0], entry.getValue()[1]);
                shapeRepository.save(shape);
            }
        }
        // 重建新节点动作（带新坐标）
        return rebuildWithPositions(actions, positions);
    }

    /** 用布局坐标重建 CreateShape 动作（无坐标的节点保持原样） */
    private List<BoardAction> rebuildWithPositions(List<BoardAction> actions, Map<String, double[]> positions) {
        return actions.stream().map(action -> {
            if (action instanceof BoardAction.CreateShape shape && shape.id() != null) {
                double[] pos = positions.get(shape.id());
                if (pos != null) {
                    return new BoardAction.CreateShape(shape.id(), shape.title(), shape.shape(), shape.level(),
                            pos[0], pos[1],
                            shape.width() <= 0 ? 140 : shape.width(),
                            shape.height() <= 0 ? 60 : shape.height());
                }
            }
            return action;
        }).toList();
    }

    /** 计算一组布局坐标（左上角）的包围盒 [minX, minY, maxX, maxY] */
    private double[] boundingBox(Map<String, double[]> positions) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] pos : positions.values()) {
            minX = Math.min(minX, pos[0]);
            minY = Math.min(minY, pos[1]);
            maxX = Math.max(maxX, pos[0] + SHAPE_W);
            maxY = Math.max(maxY, pos[1] + SHAPE_H);
        }
        return new double[] { minX, minY, maxX, maxY };
    }

    /** 计算画布现有图形节点的包围盒；画布为空返回 null */
    private double[] existingBounds(UUID boardId) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        boolean any = false;
        for (Shape shape : shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId)) {
            minX = Math.min(minX, shape.getX());
            minY = Math.min(minY, shape.getY());
            maxX = Math.max(maxX, shape.getX() + shape.getWidth());
            maxY = Math.max(maxY, shape.getY() + shape.getHeight());
            any = true;
        }
        return any ? new double[] { minX, minY, maxX, maxY } : null;
    }

    /**
     * 在两点之间创建连线（board_edges）。
     * fromShapeId/toShapeId 可能是本计划新建节点的临时 id（如 root1），
     * 也可能是已有节点的真实 UUID——统一通过 shapeIdMap 解析。
     */
    private void applyConnect(AiTask task, BoardAction.Connect action, Map<String, UUID> shapeIdMap) {
        UUID fromShapeId = resolveShapeId(action.fromShapeId(), shapeIdMap);
        UUID toShapeId = resolveShapeId(action.toShapeId(), shapeIdMap);
        if (fromShapeId == null || toShapeId == null || fromShapeId.equals(toShapeId)) {
            return;
        }
        // 校验起点/终点存在（图形节点）
        boolean fromExists = nodeExistsInBoard(fromShapeId, task.getBoardId());
        boolean toExists = nodeExistsInBoard(toShapeId, task.getBoardId());
        if (!fromExists || !toExists) {
            return;
        }
        log.info("applyConnect: {} -> {} label={}", fromShapeId, toShapeId, action.label());
        edgeRepository.save(new BoardEdge(task.getBoardId(), fromShapeId, toShapeId, action.label(),
                action.style(), action.color(), action.weight() == null ? 2 : action.weight()));
    }

    /** 节点是否存在于当前画布（图形节点） */
    private boolean nodeExistsInBoard(UUID nodeId, UUID boardId) {
        return shapeRepository.findById(nodeId)
                .map(shape -> shape.getBoardId().equals(boardId))
                .orElse(false);
    }

    /**
     * 把连线端点解析为真实 UUID。
     * 优先查本计划新建节点的临时 id 映射（shapeIdMap），否则尝试把字符串当 UUID 解析。
     */
    private UUID resolveShapeId(String shapeId, Map<String, UUID> shapeIdMap) {
        if (shapeId == null || shapeId.isBlank()) {
            return null;
        }
        UUID mapped = shapeIdMap.get(shapeId);
        if (mapped != null) {
            return mapped;
        }
        return uuid(shapeId);
    }

    /** 删除图形节点（连带其引用的连线） */
    private void applyDeleteShape(AiTask task, BoardAction.DeleteShape action) {
        UUID shapeId = uuid(action.shapeId());
        if (shapeId == null) {
            log.warn("applyDeleteShape: 忽略非法 shapeId={}", action.shapeId());
            return;
        }
        shapeRepository.findById(shapeId)
                .filter(shape -> shape.getBoardId().equals(task.getBoardId()))
                .ifPresentOrElse(shape -> {
                    List<BoardEdge> edges = edgeRepository.findByFromShapeIdOrToShapeId(shapeId, shapeId);
                    edgeRepository.deleteAll(edges);
                    shapeRepository.delete(shape);
                    log.info("applyDeleteShape: shapeId={} title={} 已删除", shapeId, shape.getTitle());
                }, () -> log.warn("applyDeleteShape: 图形节点不存在 shapeId={}", shapeId));
    }

    /** 字符串转 UUID；非法返回 null */
    private UUID uuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** 把选中元素 id 列表 + 后续任务计划序列化为 JSON，存入任务 context_json（供异步执行时还原） */
    private String contextJson(List<String> shapeIds, List<AiTaskType> remainingPlan) {
        Map<String, Object> payload = Map.of(
                "selectedShapeIds", shapeIds == null ? List.of() : shapeIds,
                "plan", remainingPlan == null ? List.of() : remainingPlan.stream().map(AiTaskType::name).toList());
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception exception) {
            log.warn("序列化任务上下文失败：{}", exception.getMessage());
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

    /** 从 context_json 解析后续任务计划（有序任务类型列表），缺失/非法/为空返回空列表 */
    private List<AiTaskType> parsePlan(String contextJson) {
        return parseStringList(contextJson, "plan").stream()
                .map(name -> {
                    try {
                        return AiTaskType.valueOf(name);
                    } catch (IllegalArgumentException exception) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 查选中的图形节点内容（过滤不属于当前画布的，避免越权） */
    private List<AiTaskContext.ShapeContext> selectedShapes(UUID boardId, String contextJson) {
        return parseStringList(contextJson, "selectedShapeIds").stream()
                .map(this::uuid)
                .filter(id -> id != null)
                .map(shapeRepository::findById)
                .flatMap(java.util.Optional::stream)
                .filter(shape -> shape.getBoardId().equals(boardId))
                .map(shape -> new AiTaskContext.ShapeContext(shape.getId().toString(), shape.getTitle(),
                        shape.getShape(), shape.getLevel()))
                .toList();
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

    /**
     * 睡眠并周期性检查是否被中断。
     * 用于模拟任务步骤的耗时，同时让中断尽快生效。
     */
    private void sleepAndCheck(UUID taskId, long millis) {
        Instant deadline = Instant.now().plusMillis(millis);
        while (Instant.now().isBefore(deadline)) {
            assertNotCancelled(taskId);
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AbortTaskException();
            }
        }
    }

    /**
     * 检查任务是否被中断。
     * 双重标记：DB abort_requested 字段 + Redis cancel key（abort 接口设置）。
     */
    private void assertNotCancelled(UUID taskId) {
        String redisFlag = redisTemplate.opsForValue().get(cancelKey(taskId));
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        if (task.isAbortRequested() || "1".equals(redisFlag)) {
            throw new AbortTaskException();
        }
    }

    /** Redis 取消标记的 key */
    private String cancelKey(UUID taskId) {
        return "ai-task:cancel:%s".formatted(taskId);
    }

    /** 中断异常：捕获后标记任务为 ABORTED（区别于普通失败） */
    private static class AbortTaskException extends RuntimeException {
    }
}
