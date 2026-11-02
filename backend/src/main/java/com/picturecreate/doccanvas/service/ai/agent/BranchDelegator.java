package com.picturecreate.doccanvas.service.ai.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.domain.AgentStepType;
import com.picturecreate.doccanvas.domain.AiTaskType;
import com.picturecreate.doccanvas.service.ai.BoardAction;
import com.picturecreate.doccanvas.service.ai.PromptCatalog;
import com.picturecreate.doccanvas.service.ai.SkillDefinition;
import com.picturecreate.doccanvas.service.ai.llm.LlmGateway;
import com.picturecreate.doccanvas.service.ai.llm.LlmOperation;
import com.picturecreate.doccanvas.service.ai.llm.LlmRequest;
import com.picturecreate.doccanvas.service.ai.llm.LlmResult;
import com.picturecreate.doccanvas.service.ai.llm.TaskAbortedException;
import com.picturecreate.doccanvas.service.ai.llm.TaskRuntime;

import jakarta.annotation.PreDestroy;

/**
 * 树型大图的分支委派：Supervisor 先拆出根节点与一级分支，BranchWorker 并行展开各分支，
 * 最后由本类把结果确定性合并为一批画布动作。
 *
 * 合并结果仍是「一次事务应用一批动作」，不做逐节点写画布；
 * 去重、层级、连线都在内存里完成，任何分支失败都按 allowPartial 决定整体是否失败。
 */
@Component
public class BranchDelegator {

    private static final Logger log = LoggerFactory.getLogger(BranchDelegator.class);

    /** 需要按分支维护父子层级的树型任务 */
    private static final Set<AiTaskType> TREE_TYPES = Set.of(AiTaskType.GENERATE_MINDMAP,
            AiTaskType.DOCUMENT_SUMMARY, AiTaskType.STUDY_PLAN, AiTaskType.PRODUCT_REQUIREMENT);

    /** 分支计划最多尝试次数：首次 + 带错误反馈重试一次 */
    private static final int PLAN_ATTEMPTS = 2;

    private final LlmGateway gateway;
    private final PromptCatalog promptCatalog;
    private final AgentToolRegistry toolRegistry;
    private final AgentRunRecorder recorder;
    private final BranchWorkerAgent worker;
    private final AppProperties properties;
    private final ExecutorService branchPool;
    private final AtomicInteger threadSeq = new AtomicInteger();

    public BranchDelegator(LlmGateway gateway, PromptCatalog promptCatalog, AgentToolRegistry toolRegistry,
            AgentRunRecorder recorder, BranchWorkerAgent worker, AppProperties properties) {
        this.gateway = gateway;
        this.promptCatalog = promptCatalog;
        this.toolRegistry = toolRegistry;
        this.recorder = recorder;
        this.worker = worker;
        this.properties = properties;
        int parallelism = Math.max(1, properties.ai().agent().branchParallelism());
        this.branchPool = Executors.newFixedThreadPool(parallelism, task -> {
            Thread thread = new Thread(task, "agent-branch-worker-" + threadSeq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        log.info("分支委派线程池已创建：并发={}", parallelism);
    }

    @PreDestroy
    public void shutdown() {
        branchPool.shutdownNow();
    }

    /** 是否由本类执行：只在开启委派且为树型任务时生效，其余任务保持单 Agent 生成 */
    public boolean supports(AiTaskType type) {
        return properties.ai().agent().delegationEnabled() && TREE_TYPES.contains(type);
    }

    /**
     * 生成一批画布动作：拆分支 → 并行展开 → 合并。
     * 中途中断抛 TaskAbortedException；分支失败按配置决定是否整体失败。
     */
    public List<BoardAction> generate(AgentExecutionContext context, SkillDefinition skill) {
        AppProperties.Agent config = properties.ai().agent();
        TaskRuntime runtime = context.runtime();
        runtime.report("AI 正在拆解大图分支", 20);
        long start = System.nanoTime();
        BranchPlan plan = planBranches(context, skill, config);
        log.info("分支计划：taskId={} 根节点={} 分支数={} keys={}", context.taskId(), plan.rootTitle(),
                plan.branches().size(), plan.branches().stream().map(BranchSpec::key).toList());
        runtime.report("已拆分为 " + plan.branches().size() + " 个分支，正在并行展开", 30);
        List<BranchResult> results = runBranches(context, plan, skill, runtime);
        recordDelegateQuietly(context, plan, results, elapsedMs(start));
        BranchResult aborted = results.stream().filter(BranchResult::aborted).findFirst().orElse(null);
        if (aborted != null) {
            throw new TaskAbortedException();
        }
        List<BranchResult> failed = results.stream().filter(result -> !result.ok()).toList();
        if (!failed.isEmpty() && !config.delegateAllowPartial()) {
            throw new IllegalStateException("分支生成失败：" + describeFailures(failed));
        }
        List<BranchResult> succeeded = results.stream().filter(BranchResult::ok).toList();
        if (succeeded.isEmpty()) {
            throw new IllegalStateException("所有分支均未生成有效结果：" + describeFailures(failed));
        }
        MergeOutcome merged = merge(plan, succeeded);
        recordMergeQuietly(context, succeeded, merged);
        if (merged.skipped() > 0) {
            log.warn("合并分支时跳过了重复节点：taskId={} 跳过={}", context.taskId(), merged.skipped());
        }
        runtime.report("分支合并完成，准备写入画布", 90);
        return merged.actions();
    }

    /** Supervisor 拆分支：调用模型并解析 JSON，解析/校验失败时带原因重试一次 */
    private BranchPlan planBranches(AgentExecutionContext context, SkillDefinition skill,
            AppProperties.Agent config) {
        List<String> toolNames = toolRegistry.generationToolNames();
        String system = promptCatalog.branchOutlinePrompt(context.boardId(), context.taskTypeHint(), toolNames,
                context.prompt(), skillBody(skill), config.maxBranches(), config.branchMaxChildren());
        String previousError = null;
        for (int attempt = 1; attempt <= PLAN_ATTEMPTS; attempt++) {
            context.runtime().check();
            LlmRequest request = new LlmRequest(LlmOperation.AGENT, system,
                    planUserPrompt(context, previousError), toolRegistry.generationTools(context),
                    AgentToolContext.of(context), 0.2, context.runtime(),
                    recorder.modelCallListener(context.runId()));
            LlmResult llm = gateway.call(request);
            try {
                return BranchPlanParser.parse(llm.content(), config.maxBranches(), config.branchMaxChildren());
            } catch (IllegalArgumentException exception) {
                previousError = exception.getMessage();
                log.warn("分支计划未通过校验，准备重试：taskId={} 尝试={}/{} 原因={}", context.taskId(), attempt,
                        PLAN_ATTEMPTS, previousError);
            }
        }
        throw new IllegalStateException("分支计划未通过校验：" + previousError);
    }

    /** 提交分支任务并按完成顺序收结果；每个分支完成即上报计划进度 */
    private List<BranchResult> runBranches(AgentExecutionContext context, BranchPlan plan,
            SkillDefinition skill, TaskRuntime runtime) {
        int total = plan.branches().size();
        ExecutorCompletionService<BranchResult> completion = new ExecutorCompletionService<>(branchPool);
        List<Future<BranchResult>> futures = new ArrayList<>();
        for (BranchSpec branch : plan.branches()) {
            futures.add(completion.submit(
                    () -> worker.run(context, branch, plan.rootTitle(), context.prompt(), skill)));
        }
        List<BranchResult> results = new ArrayList<>();
        for (int completed = 1; completed <= total; completed++) {
            BranchResult result;
            Future<BranchResult> future;
            try {
                future = completion.take();
                result = future.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                cancelRemaining(futures);
                throw new IllegalStateException("等待分支结果时被中断");
            } catch (ExecutionException exception) {
                cancelRemaining(futures);
                throw new IllegalStateException("分支执行异常：" + message(exception.getCause()));
            }
            results.add(result);
            String progress = result.ok()
                    ? "分支完成 " + completed + "/" + total + "：" + result.title()
                    : "分支失败 " + completed + "/" + total + "：" + result.title() + "（" + result.error() + "）";
            runtime.report(progress, 30 + completed * 50 / total);
        }
        return results;
    }

    /** 合并成功分支：根节点 + 一级分支 + 子节点；同一父节点下标题归一化后重复的跳过 */
    private MergeOutcome merge(BranchPlan plan, List<BranchResult> succeeded) {
        List<BoardAction> actions = new ArrayList<>();
        actions.add(new BoardAction.CreateShape("root", plan.rootTitle(), "ellipse", 0, 0, 0, 200, 96));
        Set<String> branchTitles = new HashSet<>();
        int skipped = 0;
        for (BranchResult result : succeeded) {
            if (!branchTitles.add(BranchWorkerAgent.normalize(result.title()))) {
                skipped++;
                continue;
            }
            String branchId = "branch-" + result.key();
            actions.add(new BoardAction.CreateShape(branchId, result.title(), "rectangle", 1, 0, 0, 180, 72));
            actions.add(new BoardAction.Connect("root", branchId, "", "curve", null, 2));
            Set<String> childTitles = new HashSet<>();
            int childIndex = 0;
            for (String child : result.children()) {
                if (!childTitles.add(BranchWorkerAgent.normalize(child))) {
                    skipped++;
                    continue;
                }
                String childId = branchId + "-c" + (++childIndex);
                actions.add(new BoardAction.CreateShape(childId, child, "oval", 2, 0, 0, 160, 60));
                actions.add(new BoardAction.Connect(branchId, childId, "", "curve", null, 1));
            }
        }
        log.info("合并分支结果：根节点={} 成功分支={} 动作数={} 跳过重复={}", plan.rootTitle(), succeeded.size(),
                actions.size(), skipped);
        return new MergeOutcome(actions, skipped);
    }

    /** 异常退出时取消仍在排队或执行的分支，避免后台线程在计划结束后继续写运行记录 */
    private void cancelRemaining(List<Future<BranchResult>> futures) {
        for (Future<BranchResult> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    /** Supervisor 的 user prompt：任务类型、全局目标与上次失败原因 */
    private String planUserPrompt(AgentExecutionContext context, String previousError) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("任务类型：").append(context.taskTypeHint()).append('\n');
        prompt.append("用户需求：").append(context.prompt()).append('\n');
        if (previousError != null && !previousError.isBlank()) {
            prompt.append("\n注意：你上一次的输出未通过校验，原因：").append(previousError)
                    .append("。请修复后重新输出，不要重复相同错误。\n");
        }
        return prompt.toString();
    }

    private void recordDelegateQuietly(AgentExecutionContext context, BranchPlan plan,
            List<BranchResult> results, long latencyMs) {
        Map<String, Object> input = Map.of("root", plan.rootTitle(), "branches", plan.branches());
        String error = results.stream().filter(result -> !result.ok())
                .map(result -> result.key() + "：" + result.error())
                .reduce((left, right) -> left + "；" + right).orElse(null);
        recordStepQuietly(context.runId(), AgentStepType.DELEGATE, "delegate_branches", input, results, latencyMs,
                error);
    }

    private void recordMergeQuietly(AgentExecutionContext context, List<BranchResult> succeeded,
            MergeOutcome merged) {
        Map<String, Object> input = Map.of("branches", succeeded.stream().map(BranchResult::key).toList());
        Map<String, Object> output = Map.of("actions", merged.actions().size(), "skipped", merged.skipped());
        recordStepQuietly(context.runId(), AgentStepType.MERGE, "merge_branch_results", input, output, 0, null);
    }

    private void recordStepQuietly(UUID runId, AgentStepType type, String toolName, Object input, Object output,
            long latencyMs, String errorMessage) {
        try {
            recorder.recordStep(runId, type, toolName, input, output, latencyMs, errorMessage);
        } catch (RuntimeException exception) {
            log.warn("记录 Agent 步骤失败：runId={} type={} error={}", runId, type, exception.getMessage());
        }
    }

    private String describeFailures(List<BranchResult> failed) {
        return failed.stream()
                .map(result -> result.key() + "（" + result.error() + "）")
                .reduce((left, right) -> left + "、" + right)
                .orElse("未知原因");
    }

    private String skillBody(SkillDefinition skill) {
        return skill == null ? null : skill.body();
    }

    private String message(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 合并结果：动作列表与跳过的重复节点数 */
    private record MergeOutcome(List<BoardAction> actions, int skipped) {
    }
}
