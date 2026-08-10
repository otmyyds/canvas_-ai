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
import com.picturecreate.doccanvas.repository.CardRepository;
import com.picturecreate.doccanvas.repository.ShapeRepository;
import com.picturecreate.doccanvas.service.ai.AiGenerationService;
import com.picturecreate.doccanvas.service.ai.AiTaskContext;
import com.picturecreate.doccanvas.service.ai.BoardAction;
import com.picturecreate.doccanvas.service.ai.BoardActionResult;
import com.picturecreate.doccanvas.service.ai.GraphLayoutService;
import com.picturecreate.doccanvas.service.ai.SkillDefinition;
import com.picturecreate.doccanvas.service.ai.SkillLoader;

@Service
public class AiTaskService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiTaskService.class);

    private static final List<AiTaskStatus> ACTIVE_STATUSES = List.of(AiTaskStatus.QUEUED, AiTaskStatus.RUNNING,
            AiTaskStatus.ABORTING);

    private final BoardService boardService;
    private final AiTaskRepository taskRepository;
    private final AiTaskStepRepository stepRepository;
    private final CardRepository cardRepository;
    private final BoardEdgeRepository edgeRepository;
    private final ShapeRepository shapeRepository;
    private final FileService fileService;
    private final SkillLoader skillLoader;
    private final TaskEventPublisher eventPublisher;
    private final AiGenerationService aiGenerationService;
    private final Executor aiTaskExecutor;
    private final StringRedisTemplate redisTemplate;
    private final AppProperties properties;

    public AiTaskService(BoardService boardService, AiTaskRepository taskRepository, AiTaskStepRepository stepRepository,
            CardRepository cardRepository, BoardEdgeRepository edgeRepository, ShapeRepository shapeRepository,
            FileService fileService, SkillLoader skillLoader, TaskEventPublisher eventPublisher,
            AiGenerationService aiGenerationService, @Qualifier("aiTaskExecutor") Executor aiTaskExecutor,
            StringRedisTemplate redisTemplate, AppProperties properties) {
        this.boardService = boardService;
        this.taskRepository = taskRepository;
        this.stepRepository = stepRepository;
        this.cardRepository = cardRepository;
        this.edgeRepository = edgeRepository;
        this.shapeRepository = shapeRepository;
        this.fileService = fileService;
        this.skillLoader = skillLoader;
        this.eventPublisher = eventPublisher;
        this.aiGenerationService = aiGenerationService;
        this.aiTaskExecutor = aiTaskExecutor;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Transactional
    public AiTaskResponse create(UUID boardId, CurrentUser user, AiTaskType type, String prompt) {
        boardService.getOwned(boardId, user);
        enforceConcurrency(boardId, user);
        AiTask task = taskRepository.save(new AiTask(boardId, user.id(), type, prompt));
        eventPublisher.publish(boardId, task.getId(), TaskEvent.created(task.getId(), boardId, type.name()));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                aiTaskExecutor.execute(() -> runTask(task.getId()));
            }
        });
        return get(task.getId(), user);
    }

    @Transactional(readOnly = true)
    public List<AiTaskResponse> list(UUID boardId, CurrentUser user) {
        boardService.getOwned(boardId, user);
        return taskRepository.findByBoardIdAndOwnerIdOrderByCreatedAtDesc(boardId, user.id()).stream()
                .map(task -> AiTaskResponse.from(task, stepRepository.findByTaskIdOrderByCreatedAtAsc(task.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AiTaskResponse get(UUID taskId, CurrentUser user) {
        AiTask task = taskRepository.findByIdAndOwnerId(taskId, user.id())
                .orElseThrow(() -> new NotFoundException("AI 任务不存在"));
        return AiTaskResponse.from(task, stepRepository.findByTaskIdOrderByCreatedAtAsc(taskId));
    }

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

    private void runTask(UUID taskId) {
        try {
            begin(taskId);
            AiTask task = taskRepository.findById(taskId).orElseThrow();
            SkillDefinition skill = skillLoader.findForTask(task.getType().name()).orElse(null);
            String skillStep = skill == null
                    ? "未找到匹配技能，按通用流程执行"
                    : "已加载技能[" + skill.name() + "]，工具范围：" + String.join("、", skill.allowedTools());
            runStep(taskId, "加载技能", 15, skillStep);
            runStep(taskId, "收集上下文", 35, "已收集画布卡片、资料文件和用户提示");
            runStep(taskId, "规划操作", 65, "已生成结构化画布操作方案");
            BoardActionResult result = generate(taskId, skill);
            runStep(taskId, "应用操作", 90, "已应用后端校验通过的画布操作");
            finish(taskId, result);
        } catch (AbortTaskException exception) {
            markAborted(taskId);
        } catch (Exception exception) {
            markFailed(taskId, exception);
        }
    }

    @Transactional
    protected void begin(UUID taskId) {
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        task.markRunning("本地执行器");
        task.updateProgress(3, "启动任务");
        taskRepository.save(task);
        eventPublisher.publish(task.getBoardId(), taskId, TaskEvent.progress(taskId, task.getBoardId(), 3,
                "任务已启动，正在准备执行"));
    }

    @Transactional
    protected BoardActionResult generate(UUID taskId, SkillDefinition skill) {
        assertNotCancelled(taskId);
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        List<AiTaskContext.SourceContext> sources = fileService.parsedSources(task.getBoardId(), task.getOwnerId());
        List<AiTaskContext.CardContext> cards = cardRepository.findByBoardIdOrderByCreatedAtAsc(task.getBoardId()).stream()
                .map(card -> new AiTaskContext.CardContext(card.getId().toString(), card.getTitle(),
                        card.getCardType().name(), card.getContentMarkdown()))
                .limit(20)
                .toList();
        return aiGenerationService.generateBoardActions(new AiTaskContext(task.getId(), task.getBoardId(),
                task.getOwnerId(), task.getType(), task.getPrompt(), sources, cards, skill));
    }

    @Transactional
    protected void finish(UUID taskId, BoardActionResult result) {
        assertNotCancelled(taskId);
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        applyActions(task, result.actions());
        task.markSucceeded(result.resultJson());
        taskRepository.save(task);
        eventPublisher.publish(task.getBoardId(), taskId, TaskEvent.actions(taskId, task.getBoardId(), result.actions()));
        eventPublisher.publish(task.getBoardId(), taskId, TaskEvent.done(taskId, task.getBoardId()));
    }

    @Transactional
    protected void markAborted(UUID taskId) {
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        task.markAborted();
        taskRepository.save(task);
        eventPublisher.publish(task.getBoardId(), taskId, TaskEvent.aborted(taskId, task.getBoardId()));
    }

    @Transactional
    protected void markFailed(UUID taskId, Exception exception) {
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        task.markFailed(exception.getMessage());
        taskRepository.save(task);
        eventPublisher.publish(task.getBoardId(), taskId, TaskEvent.failed(taskId, task.getBoardId(),
                exception.getMessage() == null ? "任务失败" : exception.getMessage()));
    }

    private void runStep(UUID taskId, String title, int taskProgress, String message) {
        AiTaskStep step = startStep(taskId, title, message);
        sleepAndCheck(taskId, 600);
        updateStep(taskId, step.getId(), 60, message);
        sleepAndCheck(taskId, 600);
        completeStep(taskId, step.getId(), taskProgress, message);
    }

    @Transactional
    protected AiTaskStep startStep(UUID taskId, String title, String message) {
        assertNotCancelled(taskId);
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        AiTaskStep step = new AiTaskStep(taskId, title);
        step.start(message);
        step = stepRepository.save(step);
        task.updateProgress(task.getProgress(), title);
        taskRepository.save(task);
        eventPublisher.publish(task.getBoardId(), taskId,
                TaskEvent.step(taskId, task.getBoardId(), step.getId(), title, "running", 0, message));
        return step;
    }

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

    @Transactional
    protected void completeStep(UUID taskId, UUID stepId, int taskProgress, String message) {
        assertNotCancelled(taskId);
        AiTaskStep step = stepRepository.findById(stepId).orElseThrow();
        step.done(message);
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        task.updateProgress(taskProgress, step.getTitle());
        stepRepository.save(step);
        taskRepository.save(task);
        eventPublisher.publish(task.getBoardId(), taskId,
                TaskEvent.step(taskId, task.getBoardId(), stepId, step.getTitle(), "done", 100, message));
        eventPublisher.publish(task.getBoardId(), taskId,
                TaskEvent.progress(taskId, task.getBoardId(), taskProgress, message));
    }

    private void applyActions(AiTask task, List<BoardAction> actions) {
        if (actions == null) {
            return;
        }
        // 结构图任务：先用布局算法为节点计算坐标，再统一落库
        List<BoardAction> placed = maybeLayoutGraph(task, actions);
        // 结构图场景下 connect 会引用本计划内新创建的节点，故分两遍：
        // 第一遍先创建所有卡片/图形并记录 临时id->真实id 映射，第二遍处理连线/更新/删除。
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
                case BoardAction.CreateCard ignored -> {
                    // 第一遍已创建
                }
                case BoardAction.CreateShape ignored -> {
                    // 第一遍已创建
                }
            }
        }
    }

    /** 创建图形节点（思维导图/流程图节点） */
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
     * 对思维导图/流程图任务，把模型输出的 create_card 用布局算法重算坐标。
     * 模型只管结构（节点 + 连线），坐标交给后端算法，避免重叠。
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
        Map<String, double[]> positions = task.getType() == AiTaskType.GENERATE_MINDMAP
                ? GraphLayoutService.layoutMindmap3(nodeIds, edges, nodeLevel)
                : GraphLayoutService.layoutFlowchart(nodeIds, edges);
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

    private UUID applyCreateCard(AiTask task, BoardAction.CreateCard action) {
        CardType cardType = parseCardType(action.cardType());
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
        cardRepository.findById(cardId)
                .filter(card -> card.getBoardId().equals(task.getBoardId()))
                .ifPresent(card -> {
                    card.update(action.title(), action.markdown(), null, null, null, null, null);
                    cardRepository.save(card);
                });
    }

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

    /** 先按本计划新建卡片的临时 id 解析，再按真实 UUID 解析 */
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

    private void applyDeleteCard(AiTask task, BoardAction.DeleteCard action) {
        UUID cardId = uuid(action.cardId());
        if (cardId == null) {
            return;
        }
        cardRepository.findById(cardId)
                .filter(card -> card.getBoardId().equals(task.getBoardId()))
                .ifPresent(card -> {
                    List<BoardEdge> edges = edgeRepository.findByFromCardIdOrToCardId(cardId, cardId);
                    edgeRepository.deleteAll(edges);
                    cardRepository.delete(card);
                });
    }

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

    private void assertNotCancelled(UUID taskId) {
        String redisFlag = redisTemplate.opsForValue().get(cancelKey(taskId));
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        if (task.isAbortRequested() || "1".equals(redisFlag)) {
            throw new AbortTaskException();
        }
    }

    private String cancelKey(UUID taskId) {
        return "ai-task:cancel:%s".formatted(taskId);
    }

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

    private static class AbortTaskException extends RuntimeException {
    }
}
