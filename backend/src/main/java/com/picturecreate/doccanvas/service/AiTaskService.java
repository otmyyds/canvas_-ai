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
import com.picturecreate.doccanvas.domain.Card;
import com.picturecreate.doccanvas.domain.CardType;
import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.repository.AiTaskRepository;
import com.picturecreate.doccanvas.repository.AiTaskStepRepository;
import com.picturecreate.doccanvas.repository.BoardEdgeRepository;
import com.picturecreate.doccanvas.repository.BoardSnapshotRepository;
import com.picturecreate.doccanvas.repository.CardRepository;
import com.picturecreate.doccanvas.repository.ShapeRepository;
import com.picturecreate.doccanvas.service.ai.AiGenerationService;
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
 * 4. 应用操作阶段 applyActions() 把模型产出的 BoardAction 落库（建卡/建图形/连线）
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
    private final CardRepository cardRepository;
    private final BoardEdgeRepository edgeRepository;
    private final ShapeRepository shapeRepository;
    private final BoardSnapshotRepository snapshotRepository;
    private final SkillLoader skillLoader;
    private final TaskEventPublisher eventPublisher;
    private final AiGenerationService aiGenerationService;
    private final Executor aiTaskExecutor;
    private final StringRedisTemplate redisTemplate;
    private final AppProperties properties;

    public AiTaskService(BoardService boardService, AiTaskRepository taskRepository, AiTaskStepRepository stepRepository,
            CardRepository cardRepository, BoardEdgeRepository edgeRepository, ShapeRepository shapeRepository,
            BoardSnapshotRepository snapshotRepository, SkillLoader skillLoader,
            TaskEventPublisher eventPublisher, AiGenerationService aiGenerationService,
            @Qualifier("aiTaskExecutor") Executor aiTaskExecutor, StringRedisTemplate redisTemplate,
            AppProperties properties) {
        this.boardService = boardService;
        this.taskRepository = taskRepository;
        this.stepRepository = stepRepository;
        this.cardRepository = cardRepository;
        this.edgeRepository = edgeRepository;
        this.shapeRepository = shapeRepository;
        this.snapshotRepository = snapshotRepository;
        this.skillLoader = skillLoader;
        this.eventPublisher = eventPublisher;
        this.aiGenerationService = aiGenerationService;
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
     * @return 创建后的任务详情（含步骤）
     */
    @Transactional
    public AiTaskResponse create(UUID boardId, CurrentUser user, AiTaskType type, String prompt,
            List<String> selectedCardIds, List<String> selectedShapeIds) {
        boardService.getOwned(boardId, user);
        enforceConcurrency(boardId, user);
        AiTask task = new AiTask(boardId, user.id(), type, prompt);
        task.setContextJson(selectionJson(selectedCardIds, selectedShapeIds));
        AiTask saved = taskRepository.save(task);
        log.info("AI 任务已创建：taskId={} boardId={} type={} prompt={} 选中卡片={} 选中图形={}", saved.getId(), boardId,
                type, prompt, selectedCardIds == null ? 0 : selectedCardIds.size(),
                selectedShapeIds == null ? 0 : selectedShapeIds.size());
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
            runStep(taskId, "收集上下文", 35, "已收集画布卡片、资料文件和用户提示");
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
     * 注入：RAG 检索的相关资料片段、画布现有卡片（前 20 张）、匹配到的 Skill。
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
        // 资料检索交给模型通过 search_document_chunks 工具按需执行，服务端不再预取注入
        List<AiTaskContext.SourceContext> sources = List.of();
        // 画布现有卡片（供 AI 参考已有内容，避免重复）
        List<AiTaskContext.CardContext> cards = cardRepository.findByBoardIdOrderByCreatedAtAsc(task.getBoardId()).stream()
                .map(card -> new AiTaskContext.CardContext(card.getId().toString(), card.getTitle(),
                        card.getCardType().name(), card.getContentMarkdown()))
                .limit(20)
                .toList();
        // 画布现有图形节点（思维导图/流程图节点，供 AI 参考与删除）
        List<AiTaskContext.ShapeContext> shapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(task.getBoardId())
                .stream()
                .map(shape -> new AiTaskContext.ShapeContext(shape.getId().toString(), shape.getTitle(),
                        shape.getShape(), shape.getLevel()))
                .toList();
        // 选中的卡片/图形节点（编辑类任务的操作目标，按持久化的 id 列表查内容）
        List<AiTaskContext.CardContext> selectedCards = selectedCards(task.getBoardId(), task.getContextJson());
        List<AiTaskContext.ShapeContext> selectedShapes = selectedShapes(task.getBoardId(), task.getContextJson());
        log.info("构建上下文：taskId={} 资料数={} 卡片数={} 图形数={} 选中卡片={} 选中图形={} skill={}", taskId,
                sources.size(), cards.size(), shapes.size(), selectedCards.size(), selectedShapes.size(),
                skill == null ? "无" : skill.slug());
        return aiGenerationService.generateBoardActions(new AiTaskContext(task.getId(), task.getBoardId(),
                task.getOwnerId(), task.getType(), task.getPrompt(), sources, cards, shapes, skill, selectedCards,
                selectedShapes));
    }

    /**
     * 清空画布：删除全部卡片、图形节点、连线以及 tldraw 快照（确定性操作，不依赖 LLM）。
     * 画布渲染有两套数据源：cards/shapes/edges 表（AI 生成）+ board_snapshots 快照（手动元素），
     * 必须同时清空，否则快照里的元素会在前端刷新后残留。
     * 供 CLEAR_CANVAS 任务调用，返回空动作，后续 finish 正常收尾并推送 done 事件。
     */
    private BoardActionResult clearCanvas(AiTask task) {
        UUID boardId = task.getBoardId();
        List<Card> cards = cardRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
        List<Shape> shapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
        List<BoardEdge> edges = edgeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
        cardRepository.deleteAll(cards);
        shapeRepository.deleteAll(shapes);
        edgeRepository.deleteAll(edges);
        snapshotRepository.deleteByBoardId(boardId);
        log.info("清空画布：boardId={} 删除卡片={} 图形={} 连线={} 快照已清空", boardId, cards.size(), shapes.size(),
                edges.size());
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
     * 若先建卡建图形（第一遍），记录 临时id→真实UUID 映射；再连/改/删（第二遍），
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
        // 第一遍：创建所有卡片/图形，记录 临时id->真实id 映射
        Map<String, UUID> cardIdMap = new HashMap<>();
        for (BoardAction action : placed) {
            if (action instanceof BoardAction.CreateCard createCard) {
                UUID created = applyCreateCard(task, createCard);
                if (created != null && createCard.id() != null && !createCard.id().isBlank()) {
                    cardIdMap.put(createCard.id(), created);
                }
            } else if (action instanceof BoardAction.CreateShape createShape) {
                UUID created = applyCreateShape(task, createShape);
                if (created != null && createShape.id() != null && !createShape.id().isBlank()) {
                    cardIdMap.put(createShape.id(), created);
                }
            }
        }
        // 第二遍：处理连线/更新/删除（connect 通过 cardIdMap 解析临时 id）
        for (BoardAction action : placed) {
            if (action == null) {
                continue;
            }
            switch (action) {
                case BoardAction.UpdateCard updateCard -> applyUpdateCard(task, updateCard);
                case BoardAction.Connect connect -> applyConnect(task, connect, cardIdMap);
                case BoardAction.CreateGroup ignored -> {
                    // 分组当前 MVP 无落库表，识别但不写入，避免阻塞任务
                }
                case BoardAction.DeleteCard deleteCard -> applyDeleteCard(task, deleteCard);
                case BoardAction.DeleteShape deleteShape -> applyDeleteShape(task, deleteShape);
                case BoardAction.ConvertCardToShape convert -> applyConvertCardToShape(task, convert);
                case BoardAction.ConvertShapeToCard convert -> applyConvertShapeToCard(task, convert);
                case BoardAction.CreateCard ignored -> {
                    // 第一遍已创建
                }
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
     * - 思维导图 → 递归径向树布局 + 偏移到已有内容右侧空白
     * - 流程图 → 分层布局（layoutFlowchart）
     * - 编辑扩展图形节点 → 对选中节点所在树整树重排
     */
    private List<BoardAction> maybeLayoutGraph(AiTask task, List<BoardAction> actions) {
        if (task.getType() == AiTaskType.GENERATE_MINDMAP) {
            return layoutNewMindmap(task, actions);
        }
        if (task.getType() == AiTaskType.EDIT_ELEMENT) {
            return reflowEditedTree(task, actions);
        }
        if (task.getType() != AiTaskType.GENERATE_FLOWCHART) {
            return actions;
        }
        // 流程图：收集节点与边，分层布局
        List<String> nodeIds = new ArrayList<>();
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.CreateShape shape && shape.id() != null) {
                nodeIds.add(shape.id());
            } else if (action instanceof BoardAction.CreateCard card && card.id() != null) {
                nodeIds.add(card.id());
            }
        }
        List<String[]> edges = new ArrayList<>();
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.Connect connect && connect.fromCardId() != null
                    && connect.toCardId() != null) {
                edges.add(new String[] { connect.fromCardId(), connect.toCardId() });
            }
        }
        if (nodeIds.isEmpty()) {
            return actions;
        }
        Map<String, double[]> positions = GraphLayoutService.layoutFlowchart(nodeIds, edges);
        return rebuildWithPositions(actions, positions);
    }

    /**
     * 新建思维导图：递归布局后整体平移到画布空白处（避免叠加到旧图之上）。
     * 首个导图放到原点边距；已有内容时放到其包围盒右侧。
     */
    private List<BoardAction> layoutNewMindmap(AiTask task, List<BoardAction> actions) {
        List<String> nodeIds = new ArrayList<>();
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.CreateShape shape && shape.id() != null) {
                nodeIds.add(shape.id());
            }
        }
        List<String[]> edges = new ArrayList<>();
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.Connect connect && connect.fromCardId() != null
                    && connect.toCardId() != null) {
                edges.add(new String[] { connect.fromCardId(), connect.toCardId() });
            }
        }
        if (nodeIds.isEmpty()) {
            return actions;
        }
        Map<String, double[]> positions = GraphLayoutService.layoutMindmapTree(nodeIds, edges);
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
            if (action instanceof BoardAction.Connect connect && connect.fromCardId() != null
                    && !connect.fromCardId().isBlank() && connect.toCardId() != null && !connect.toCardId().isBlank()) {
                newEdges.add(new String[] { connect.fromCardId(), connect.toCardId() });
            }
        }
        List<Shape> existingShapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(task.getBoardId());
        List<BoardEdge> existingEdges = edgeRepository.findByBoardIdOrderByCreatedAtAsc(task.getBoardId());
        Map<String, Shape> shapeById = new HashMap<>();
        for (Shape shape : existingShapes) {
            shapeById.put(shape.getId().toString(), shape);
        }
        // 无向邻接，用于从选中节点 BFS 找所在树
        Map<String, Set<String>> undirected = new HashMap<>();
        for (Shape shape : existingShapes) {
            undirected.put(shape.getId().toString(), new HashSet<>());
        }
        for (BoardEdge edge : existingEdges) {
            String from = edge.getFromCardId().toString();
            String to = edge.getToCardId().toString();
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
            String from = edge.getFromCardId().toString();
            String to = edge.getToCardId().toString();
            if (treeNodes.contains(from) && treeNodes.contains(to)) {
                treeEdges.add(new String[] { from, to });
            }
        }
        for (BoardAction.CreateShape shape : newShapes) {
            allNodes.add(shape.id());
        }
        treeEdges.addAll(newEdges);
        Map<String, double[]> positions = GraphLayoutService.layoutMindmapTree(allNodes, treeEdges);
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

    /** 用布局坐标重建 CreateShape/CreateCard 动作（无坐标的节点保持原样） */
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
            } else if (action instanceof BoardAction.CreateCard createCard && createCard.id() != null) {
                double[] pos = positions.get(createCard.id());
                if (pos != null) {
                    return new BoardAction.CreateCard(createCard.id(), createCard.title(), createCard.markdown(),
                            createCard.cardType(), pos[0], pos[1],
                            createCard.width() <= 0 ? 340 : createCard.width(),
                            createCard.height() <= 0 ? 220 : createCard.height(), createCard.sourceFileIds());
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

    /** 计算画布现有元素（卡片 + 图形节点）的包围盒；画布为空返回 null */
    private double[] existingBounds(UUID boardId) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        boolean any = false;
        for (Card card : cardRepository.findByBoardIdOrderByCreatedAtAsc(boardId)) {
            minX = Math.min(minX, card.getX());
            minY = Math.min(minY, card.getY());
            maxX = Math.max(maxX, card.getX() + card.getWidth());
            maxY = Math.max(maxY, card.getY() + card.getHeight());
            any = true;
        }
        for (Shape shape : shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId)) {
            minX = Math.min(minX, shape.getX());
            minY = Math.min(minY, shape.getY());
            maxX = Math.max(maxX, shape.getX() + shape.getWidth());
            maxY = Math.max(maxY, shape.getY() + shape.getHeight());
            any = true;
        }
        return any ? new double[] { minX, minY, maxX, maxY } : null;
    }

    /** 创建普通卡片并落库到 cards 表，返回真实 UUID */
    private UUID applyCreateCard(AiTask task, BoardAction.CreateCard action) {
        CardType cardType = parseCardType(action.cardType());
        log.info("applyCreateCard: id={} title={} type={} pos=({},{})", action.id(), action.title(), cardType,
                action.x(), action.y());
        Card card = new Card(task.getBoardId(),
                action.title() == null || action.title().isBlank() ? "AI 卡片" : action.title(),
                action.markdown() == null ? "" : action.markdown(),
                cardType,
                action.x(),
                action.y(),
                action.width() <= 0 ? 340 : action.width(),
                action.height() <= 0 ? 220 : action.height());
        return cardRepository.save(card).getId();
    }

    /** 更新已有卡片（只改标题/正文，保留位置尺寸） */
    private void applyUpdateCard(AiTask task, BoardAction.UpdateCard action) {
        if (action.cardId() == null || action.cardId().isBlank()) {
            return;
        }
        UUID cardId;
        try {
            cardId = UUID.fromString(action.cardId());
        } catch (IllegalArgumentException exception) {
            return;
        }
        log.info("applyUpdateCard: cardId={} title={}", cardId, action.title());
        cardRepository.findById(cardId)
                .filter(card -> card.getBoardId().equals(task.getBoardId()))
                .ifPresent(card -> {
                    card.update(action.title(), action.markdown(), null, null, null, null, null);
                    cardRepository.save(card);
                });
    }

    /**
     * 在两点之间创建连线（board_edges）。
     * fromCardId/toCardId 可能是本计划新建节点的临时 id（如 root1），
     * 也可能是已有节点的真实 UUID——统一通过 cardIdMap 解析。
     */
    private void applyConnect(AiTask task, BoardAction.Connect action, Map<String, UUID> cardIdMap) {
        UUID fromCardId = resolveCardId(action.fromCardId(), cardIdMap);
        UUID toCardId = resolveCardId(action.toCardId(), cardIdMap);
        if (fromCardId == null || toCardId == null || fromCardId.equals(toCardId)) {
            return;
        }
        // 校验起点/终点存在（卡片或图形节点均可）
        boolean fromExists = nodeExistsInBoard(fromCardId, task.getBoardId());
        boolean toExists = nodeExistsInBoard(toCardId, task.getBoardId());
        if (!fromExists || !toExists) {
            return;
        }
        log.info("applyConnect: {} -> {} label={}", fromCardId, toCardId, action.label());
        edgeRepository.save(new BoardEdge(task.getBoardId(), fromCardId, toCardId, action.label(),
                action.style(), action.color(), action.weight() == null ? 2 : action.weight()));
    }

    /** 节点是否存在于当前画布（卡片或图形节点） */
    private boolean nodeExistsInBoard(UUID nodeId, UUID boardId) {
        boolean cardExists = cardRepository.findById(nodeId)
                .map(card -> card.getBoardId().equals(boardId))
                .orElse(false);
        if (cardExists) {
            return true;
        }
        return shapeRepository.findById(nodeId)
                .map(shape -> shape.getBoardId().equals(boardId))
                .orElse(false);
    }

    /**
     * 把连线端点解析为真实 UUID。
     * 优先查本计划新建节点的临时 id 映射（cardIdMap），否则尝试把字符串当 UUID 解析。
     */
    private UUID resolveCardId(String cardId, Map<String, UUID> cardIdMap) {
        if (cardId == null || cardId.isBlank()) {
            return null;
        }
        UUID mapped = cardIdMap.get(cardId);
        if (mapped != null) {
            return mapped;
        }
        return uuid(cardId);
    }

    /** 删除卡片（连带其引用的连线） */
    private void applyDeleteCard(AiTask task, BoardAction.DeleteCard action) {
        UUID cardId = uuid(action.cardId());
        if (cardId == null) {
            log.warn("applyDeleteCard: 忽略非法 cardId={}", action.cardId());
            return;
        }
        cardRepository.findById(cardId)
                .filter(card -> card.getBoardId().equals(task.getBoardId()))
                .ifPresentOrElse(card -> {
                    List<BoardEdge> edges = edgeRepository.findByFromCardIdOrToCardId(cardId, cardId);
                    edgeRepository.deleteAll(edges);
                    cardRepository.delete(card);
                    log.info("applyDeleteCard: cardId={} title={} 已删除", cardId, card.getTitle());
                }, () -> log.warn("applyDeleteCard: 卡片不存在 cardId={}", cardId));
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
                    List<BoardEdge> edges = edgeRepository.findByFromCardIdOrToCardId(shapeId, shapeId);
                    edgeRepository.deleteAll(edges);
                    shapeRepository.delete(shape);
                    log.info("applyDeleteShape: shapeId={} title={} 已删除", shapeId, shape.getTitle());
                }, () -> log.warn("applyDeleteShape: 图形节点不存在 shapeId={}", shapeId));
    }

    /** 把一张卡片转换为图形节点：新建同位置图形，重定向连线，删除原卡片 */
    private void applyConvertCardToShape(AiTask task, BoardAction.ConvertCardToShape action) {
        UUID cardId = uuid(action.cardId());
        if (cardId == null) {
            log.warn("applyConvertCardToShape: 忽略非法 cardId={}", action.cardId());
            return;
        }
        cardRepository.findById(cardId)
                .filter(card -> card.getBoardId().equals(task.getBoardId()))
                .ifPresent(card -> {
                    String shape = action.shape() == null || action.shape().isBlank() ? "oval" : action.shape();
                    Shape newShape = new Shape(task.getBoardId(), card.getTitle(), shape, action.level(),
                            card.getX(), card.getY(), card.getWidth(), card.getHeight());
                    UUID newId = shapeRepository.save(newShape).getId();
                    redirectEdges(cardId, newId);
                    cardRepository.delete(card);
                    log.info("applyConvertCardToShape: 卡片 {} -> 图形 {} title={}", cardId, newId, card.getTitle());
                });
    }

    /** 把一个图形节点转换为卡片：新建同位置卡片，重定向连线，删除原图形 */
    private void applyConvertShapeToCard(AiTask task, BoardAction.ConvertShapeToCard action) {
        UUID shapeId = uuid(action.shapeId());
        if (shapeId == null) {
            log.warn("applyConvertShapeToCard: 忽略非法 shapeId={}", action.shapeId());
            return;
        }
        shapeRepository.findById(shapeId)
                .filter(shape -> shape.getBoardId().equals(task.getBoardId()))
                .ifPresent(shape -> {
                    CardType cardType = parseCardType(action.cardType() == null ? "IDEA" : action.cardType());
                    Card newCard = new Card(task.getBoardId(), shape.getTitle(), shape.getTitle(), cardType,
                            shape.getX(), shape.getY(), shape.getWidth(), shape.getHeight());
                    UUID newId = cardRepository.save(newCard).getId();
                    redirectEdges(shapeId, newId);
                    shapeRepository.delete(shape);
                    log.info("applyConvertShapeToCard: 图形 {} -> 卡片 {} title={}", shapeId, newId, shape.getTitle());
                });
    }

    /** 把引用旧节点的所有连线端点重定向到新节点 id（卡片/图形互换用） */
    private void redirectEdges(UUID oldId, UUID newId) {
        List<BoardEdge> edges = edgeRepository.findByFromCardIdOrToCardId(oldId, oldId);
        for (BoardEdge edge : edges) {
            UUID from = oldId.equals(edge.getFromCardId()) ? newId : edge.getFromCardId();
            UUID to = oldId.equals(edge.getToCardId()) ? newId : edge.getToCardId();
            edge.updateEndpoints(from, to);
            edgeRepository.save(edge);
        }
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

    /** 把选中元素 id 列表序列化为 JSON，存入任务 context_json（供异步执行时还原） */
    private String selectionJson(List<String> cardIds, List<String> shapeIds) {
        Map<String, Object> payload = Map.of(
                "selectedCardIds", cardIds == null ? List.of() : cardIds,
                "selectedShapeIds", shapeIds == null ? List.of() : shapeIds);
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception exception) {
            log.warn("序列化选中元素失败：{}", exception.getMessage());
            return null;
        }
    }

    /** 从 context_json 解析指定 key 的 id 列表，缺失/非法/为空返回空列表 */
    @SuppressWarnings("unchecked")
    private List<String> parseSelectedIds(String contextJson, String key) {
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

    /** 查选中的卡片内容（过滤不属于当前画布的，避免越权） */
    private List<AiTaskContext.CardContext> selectedCards(UUID boardId, String contextJson) {
        return parseSelectedIds(contextJson, "selectedCardIds").stream()
                .map(this::uuid)
                .filter(id -> id != null)
                .map(cardRepository::findById)
                .flatMap(java.util.Optional::stream)
                .filter(card -> card.getBoardId().equals(boardId))
                .map(card -> new AiTaskContext.CardContext(card.getId().toString(), card.getTitle(),
                        card.getCardType().name(), card.getContentMarkdown()))
                .toList();
    }

    /** 查选中的图形节点内容（过滤不属于当前画布的，避免越权） */
    private List<AiTaskContext.ShapeContext> selectedShapes(UUID boardId, String contextJson) {
        return parseSelectedIds(contextJson, "selectedShapeIds").stream()
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

    /** 解析卡片类型字符串，非法回退 SUMMARY */
    private CardType parseCardType(String value) {
        if (value == null) {
            return CardType.SUMMARY;
        }
        try {
            return CardType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return CardType.SUMMARY;
        }
    }

    /** 中断异常：捕获后标记任务为 ABORTED（区别于普通失败） */
    private static class AbortTaskException extends RuntimeException {
    }
}
