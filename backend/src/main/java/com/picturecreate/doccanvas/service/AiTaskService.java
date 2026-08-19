package com.picturecreate.doccanvas.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private final RagContextService ragContextService;
    private final SkillLoader skillLoader;
    private final TaskEventPublisher eventPublisher;
    private final AiGenerationService aiGenerationService;
    private final Executor aiTaskExecutor;
    private final StringRedisTemplate redisTemplate;
    private final AppProperties properties;

    public AiTaskService(BoardService boardService, AiTaskRepository taskRepository, AiTaskStepRepository stepRepository,
            CardRepository cardRepository, BoardEdgeRepository edgeRepository, ShapeRepository shapeRepository,
            BoardSnapshotRepository snapshotRepository, RagContextService ragContextService, SkillLoader skillLoader,
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
        this.ragContextService = ragContextService;
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
    public AiTaskResponse create(UUID boardId, CurrentUser user, AiTaskType type, String prompt) {
        boardService.getOwned(boardId, user);
        enforceConcurrency(boardId, user);
        AiTask task = taskRepository.save(new AiTask(boardId, user.id(), type, prompt));
        log.info("AI 任务已创建：taskId={} boardId={} type={} prompt={}", task.getId(), boardId, type, prompt);
        eventPublisher.publish(boardId, task.getId(), TaskEvent.created(task.getId(), boardId, type.name()));
        // 事务提交后再异步执行，保证任务记录已落库
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                aiTaskExecutor.execute(() -> runTask(task.getId()));
            }
        });
        return get(task.getId(), user);
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
        // RAG 检索的相关资料片段（替代全量注入）
        List<AiTaskContext.SourceContext> sources = ragContextService.retrieve(task.getBoardId(), task.getOwnerId(),
                task.getPrompt());
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
        log.info("构建上下文：taskId={} 资料数={} 卡片数={} 图形数={} skill={}", taskId, sources.size(), cards.size(),
                shapes.size(), skill == null ? "无" : skill.slug());
        return aiGenerationService.generateBoardActions(new AiTaskContext(task.getId(), task.getBoardId(),
                task.getOwnerId(), task.getType(), task.getPrompt(), sources, cards, shapes, skill));
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
     * 对思维导图/流程图任务，把模型输出的节点用布局算法重算坐标。
     * 模型只管结构（节点 + 连线），坐标交给后端算法，避免重叠。
     * 思维导图 → layoutMindmap3（3 层辐射布局）；流程图 → layoutFlowchart（分层布局）。
     */
    private List<BoardAction> maybeLayoutGraph(AiTask task, List<BoardAction> actions) {
        boolean isGraph = task.getType() == AiTaskType.GENERATE_MINDMAP
                || task.getType() == AiTaskType.GENERATE_FLOWCHART;
        if (!isGraph) {
            return actions;
        }
        // 收集节点：优先 CreateShape（思维导图图形节点），也兼容 CreateCard
        List<String> nodeIds = new ArrayList<>();
        Map<String, Integer> nodeLevel = new HashMap<>();
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.CreateShape shape && shape.id() != null) {
                nodeIds.add(shape.id());
                nodeLevel.put(shape.id(), shape.level());
            } else if (action instanceof BoardAction.CreateCard card && card.id() != null) {
                nodeIds.add(card.id());
                nodeLevel.put(card.id(), 0);
            }
        }
        List<String[]> edges = new ArrayList<>();
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.Connect connect) {
                edges.add(new String[] { connect.fromCardId(), connect.toCardId() });
            }
        }
        if (nodeIds.isEmpty()) {
            return actions;
        }
        // 按任务类型选择布局算法
        Map<String, double[]> positions = task.getType() == AiTaskType.GENERATE_MINDMAP
                ? GraphLayoutService.layoutMindmap3(nodeIds, edges, nodeLevel)
                : GraphLayoutService.layoutFlowchart(nodeIds, edges);
        // 用重算后的坐标重建每个节点动作
        return actions.stream().map(action -> {
            if (action instanceof BoardAction.CreateShape shape && shape.id() != null) {
                double[] pos = positions.get(shape.id());
                if (pos != null) {
                    // 用布局坐标重建 create_shape
                    return new BoardAction.CreateShape(shape.id(), shape.title(), shape.shape(), shape.level(),
                            pos[0], pos[1],
                            shape.width() <= 0 ? 140 : shape.width(),
                            shape.height() <= 0 ? 60 : shape.height());
                }
            } else if (action instanceof BoardAction.CreateCard createCard && createCard.id() != null) {
                double[] pos = positions.get(createCard.id());
                if (pos != null) {
                    // 用布局坐标重建 create_card
                    return new BoardAction.CreateCard(createCard.id(), createCard.title(), createCard.markdown(),
                            createCard.cardType(), pos[0], pos[1],
                            createCard.width() <= 0 ? 340 : createCard.width(),
                            createCard.height() <= 0 ? 220 : createCard.height(), createCard.sourceFileIds());
                }
            }
            return action;
        }).toList();
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
