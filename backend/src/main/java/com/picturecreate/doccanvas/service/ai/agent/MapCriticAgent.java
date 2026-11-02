package com.picturecreate.doccanvas.service.ai.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.domain.AgentRole;
import com.picturecreate.doccanvas.domain.AgentRun;
import com.picturecreate.doccanvas.domain.AgentRunStatus;
import com.picturecreate.doccanvas.domain.AgentStepType;
import com.picturecreate.doccanvas.domain.AiTaskType;
import com.picturecreate.doccanvas.domain.BoardEdge;
import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.repository.BoardEdgeRepository;
import com.picturecreate.doccanvas.repository.ShapeRepository;
import com.picturecreate.doccanvas.service.ai.BoardAction;
import com.picturecreate.doccanvas.service.ai.PromptCatalog;
import com.picturecreate.doccanvas.service.ai.llm.LlmGateway;
import com.picturecreate.doccanvas.service.ai.llm.LlmOperation;
import com.picturecreate.doccanvas.service.ai.llm.LlmRequest;
import com.picturecreate.doccanvas.service.ai.llm.LlmResult;
import com.picturecreate.doccanvas.service.ai.llm.TaskAbortedException;
import com.picturecreate.doccanvas.service.ai.llm.TaskRuntime;

/**
 * 语义审查 Agent：看待落库的图画快照，只输出问题列表，不修改画布。
 *
 * 审查是一次可选调用：模型异常、预算不足或输出无法解析时只记日志并跳过，不影响本步生成；
 * 只有用户中断继续向上抛出。审查结果由计划执行器决定是否回填给 Supervisor 重新生成，
 * 最多修正一轮，不重复审查。
 */
@Component
public class MapCriticAgent {

    private static final Logger log = LoggerFactory.getLogger(MapCriticAgent.class);

    /** 单次审查输出的问题上限，避免问题列表无限膨胀 */
    private static final int MAX_ISSUES = 10;

    /** 快照文本长度上限，控制审查提示词体积 */
    private static final int SNAPSHOT_MAX_LENGTH = 8000;

    /** 审查预算：独立且较小，同时计入计划总预算 */
    private static final int MAX_MODEL_CALLS = 2;
    private static final int MAX_TOOL_CALLS = 0;
    private static final int MAX_DURATION_SECONDS = 30;
    private static final long MAX_TOKENS = 8000;

    /** 用户明确要求审查时，小图也会触发 */
    private static final List<String> REVIEW_KEYWORDS = List.of("审查", "评审", "查漏补缺", "有没有问题",
            "有没有遗漏");

    private final LlmGateway gateway;
    private final PromptCatalog promptCatalog;
    private final AgentRunRecorder recorder;
    private final ShapeRepository shapeRepository;
    private final BoardEdgeRepository edgeRepository;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public MapCriticAgent(LlmGateway gateway, PromptCatalog promptCatalog, AgentRunRecorder recorder,
            ShapeRepository shapeRepository, BoardEdgeRepository edgeRepository, AppProperties properties,
            ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.promptCatalog = promptCatalog;
        this.recorder = recorder;
        this.shapeRepository = shapeRepository;
        this.edgeRepository = edgeRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 是否需要审查：开关打开，且满足以下任一条件：
     * 1. 本步新建节点数达到阈值（大图生成）；
     * 2. 用户明确要求审查（包括对既有画布做审查/修补）。
     * 小图生成默认不审查，避免增加延迟和成本。
     * critic-max-rounds 为 0 时整体关闭审查；当前管线只执行一轮审查与最多一次修正。
     */
    public boolean shouldReview(AiTaskType taskType, List<BoardAction> actions, String goal) {
        AppProperties.Agent config = properties.ai().agent();
        if (!config.criticEnabled() || config.criticMaxRounds() <= 0) {
            return false;
        }
        if (explicitlyRequested(goal)) {
            return true;
        }
        // 编辑既有画布默认不审查，避免每次小改动都为整张图付出一次模型调用
        if (taskType == AiTaskType.EDIT_ELEMENT) {
            return false;
        }
        long created = actions.stream().filter(BoardAction.CreateShape.class::isInstance).count();
        return created >= config.criticMinNodes();
    }

    /** 执行一次审查；模型/解析/预算失败都按「没有发现问题」处理，不阻断任务 */
    public CriticResult review(AgentExecutionContext parent, List<BoardAction> actions) {
        Map<String, String> titles = new LinkedHashMap<>();
        String snapshot = renderSnapshot(parent.boardId(), actions, titles);
        int nodeCount = titles.size();
        TaskRuntime runtime = TaskRuntime.branch(parent.runtime(), MAX_MODEL_CALLS, MAX_TOOL_CALLS,
                MAX_DURATION_SECONDS, MAX_TOKENS);
        AgentRun run = recorder.startRun(parent.taskId(), parent.sessionId(), parent.runId(),
                parent.user().id(), parent.boardId(), AgentRole.CRITIC, "review", null);
        String system = promptCatalog.criticPrompt(parent.taskTypeHint(), nodeCount, snapshot);
        String user = "审查目标：" + parent.prompt();
        long start = System.nanoTime();
        log.info("语义审查开始：taskId={} runId={} 节点数={} 快照长度={}", parent.taskId(), run.getId(), nodeCount,
                snapshot.length());
        try {
            LlmResult llm = gateway.call(new LlmRequest(LlmOperation.AGENT, system, user, null, null, 0.1, runtime,
                    recorder.modelCallListener(run.getId())));
            List<CriticIssue> issues = parse(llm.content(), titles);
            recordUsageQuietly(run.getId(), runtime);
            finishRunQuietly(run.getId(), AgentRunStatus.SUCCEEDED, null);
            recordStepQuietly(run.getId(), nodeCount, issues, elapsedMs(start), null);
            log.info("语义审查完成：runId={} 问题数={} 高优先级={} tokens={}", run.getId(), issues.size(),
                    issues.stream().filter(issue -> issue.severity() == CriticIssue.Severity.HIGH).count(),
                    llm.totalTokens());
            return new CriticResult(issues, titles, llm.promptTokens(), llm.completionTokens());
        } catch (TaskAbortedException exception) {
            recordUsageQuietly(run.getId(), runtime);
            finishRunQuietly(run.getId(), AgentRunStatus.ABORTED, message(exception));
            throw exception;
        } catch (RuntimeException exception) {
            recordUsageQuietly(run.getId(), runtime);
            finishRunQuietly(run.getId(), AgentRunStatus.FAILED, message(exception));
            recordStepQuietly(run.getId(), nodeCount, List.of(), elapsedMs(start), message(exception));
            log.warn("语义审查失败，跳过审查：taskId={} runId={} error={}", parent.taskId(), run.getId(),
                    message(exception));
            return CriticResult.none();
        }
    }

    /**
     * 把「既有画布 + 本步动作」还原成本步执行后的紧凑快照：节点 id、层级、标题与父子连线。
     * 审查的是结果图而不是动作列表，编辑既有大图时也能看到完整上下文。
     */
    private String renderSnapshot(UUID boardId, List<BoardAction> actions, Map<String, String> titles) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (Shape shape : shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId)) {
            titles.put(shape.getId().toString(), shape.getTitle());
            levels.put(shape.getId().toString(), shape.getLevel());
        }
        // 先应用改标题与删除，再叠加新建节点，得到本步执行后的节点集合
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.UpdateShape update && update.title() != null
                    && !update.title().isBlank()) {
                titles.computeIfPresent(update.shapeId(), (id, title) -> update.title());
                if (update.level() != null) {
                    levels.computeIfPresent(update.shapeId(), (id, level) -> update.level());
                }
            }
        }
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.DeleteShape delete) {
                titles.remove(delete.shapeId());
                levels.remove(delete.shapeId());
            }
        }
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.CreateShape shape) {
                titles.put(shape.id(), shape.title());
                levels.put(shape.id(), shape.level());
            }
        }

        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : titles.entrySet()) {
            builder.append("- ").append(entry.getKey()).append(" | 层级 ")
                    .append(levels.getOrDefault(entry.getKey(), 0)).append(" | ").append(entry.getValue())
                    .append('\n');
        }
        for (BoardEdge edge : edgeRepository.findByBoardIdOrderByCreatedAtAsc(boardId)) {
            appendEdge(builder, titles, edge.getFromShapeId().toString(), edge.getToShapeId().toString(),
                    edge.getLabel());
        }
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.Connect connect) {
                appendEdge(builder, titles, connect.fromShapeId(), connect.toShapeId(), connect.label());
            }
        }
        if (builder.length() <= SNAPSHOT_MAX_LENGTH) {
            return builder.toString();
        }
        return builder.substring(0, SNAPSHOT_MAX_LENGTH) + "……（快照已截断）";
    }

    /** 两端节点都在结果集合里才写入连线，避免把已删除节点的边带进审查 */
    private void appendEdge(StringBuilder builder, Map<String, String> titles, String fromId, String toId,
            String label) {
        if (!titles.containsKey(fromId) || !titles.containsKey(toId)) {
            return;
        }
        builder.append("- ").append(titles.get(fromId)).append(" -> ").append(titles.get(toId));
        if (label != null && !label.isBlank()) {
            builder.append("（").append(label).append("）");
        }
        builder.append('\n');
    }

    /** 解析审查输出：容忍代码围栏；非法问题条目跳过，不因单条格式错误丢弃整份结果 */
    private List<CriticIssue> parse(String content, Map<String, String> titles) {
        String json = extractJson(content);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("审查输出不是合法 JSON：" + exception.getMessage(), exception);
        }
        JsonNode array = root.path("issues");
        if (!array.isArray()) {
            throw new IllegalArgumentException("审查输出缺少 issues 数组");
        }
        List<CriticIssue> issues = new ArrayList<>();
        for (JsonNode node : array) {
            if (issues.size() >= MAX_ISSUES) {
                break;
            }
            CriticIssue issue = parseIssue(node, titles);
            if (issue != null) {
                issues.add(issue);
            }
        }
        issues.sort(Comparator.comparing(CriticIssue::severity));
        return List.copyOf(issues);
    }

    private CriticIssue parseIssue(JsonNode node, Map<String, String> titles) {
        String message = text(node, "message");
        if (message == null) {
            return null;
        }
        CriticIssue.Type type = enumValue(CriticIssue.Type.class, text(node, "type"));
        if (type == null) {
            return null;
        }
        CriticIssue.Severity severity = enumValue(CriticIssue.Severity.class, text(node, "severity"));
        String nodeId = text(node, "nodeId");
        // 未知节点 id 不保留，避免修正时指向不存在的节点
        if (nodeId != null && !titles.containsKey(nodeId)) {
            nodeId = null;
        }
        return new CriticIssue(nodeId, type, severity == null ? CriticIssue.Severity.MEDIUM : severity, message,
                text(node, "suggestion"));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** 从可能带代码围栏或前后说明的输出里截出 JSON 对象 */
    private String extractJson(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("审查输出为空");
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("审查输出中没有 JSON 对象");
        }
        return content.substring(start, end + 1);
    }

    private boolean explicitlyRequested(String goal) {
        if (goal == null || goal.isBlank()) {
            return false;
        }
        if (goal.toLowerCase(Locale.ROOT).contains("review")) {
            return true;
        }
        return REVIEW_KEYWORDS.stream().anyMatch(goal::contains);
    }

    private void recordUsageQuietly(UUID runId, TaskRuntime runtime) {
        try {
            recorder.addUsage(runId, runtime.modelCalls(), runtime.toolCalls(), runtime.promptTokens(),
                    runtime.completionTokens(), runtime.lastModel());
        } catch (RuntimeException exception) {
            log.warn("写入审查用量失败：runId={} error={}", runId, exception.getMessage());
        }
    }

    private void finishRunQuietly(UUID runId, AgentRunStatus status, String errorMessage) {
        try {
            recorder.finishRun(runId, status, null, errorMessage);
        } catch (RuntimeException exception) {
            log.warn("结束审查运行记录失败：runId={} status={} error={}", runId, status, exception.getMessage());
        }
    }

    private void recordStepQuietly(UUID runId, int nodeCount, List<CriticIssue> issues, long latencyMs,
            String errorMessage) {
        try {
            Map<String, Object> input = Map.of("nodes", nodeCount);
            recorder.recordStep(runId, AgentStepType.CRITIC, "review_map", input, issues, latencyMs,
                    errorMessage);
        } catch (RuntimeException exception) {
            log.warn("记录审查步骤失败：runId={} error={}", runId, exception.getMessage());
        }
    }

    private String message(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
