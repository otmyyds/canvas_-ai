package com.picturecreate.doccanvas.service.ai.llm;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * 单个 AI 任务的运行时预算与取消上下文。
 * 工具循环、预检、工具内部的模型调用共用同一个实例，计数必须是原子的。
 */
public final class TaskRuntime {

    private final UUID taskId;
    private final Instant deadline;
    private final int maxDurationSeconds;
    private final int maxModelCalls;
    private final int maxToolCalls;
    private final long maxTotalTokens;
    private final Runnable cancellationCheck;
    private final BiConsumer<String, Integer> progressSink;

    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicInteger progress = new AtomicInteger();
    private final AtomicBoolean degraded = new AtomicBoolean();
    private volatile String lastModel;

    private TaskRuntime(UUID taskId, int maxModelCalls, int maxToolCalls, int maxDurationSeconds,
            long maxTotalTokens, Runnable cancellationCheck, BiConsumer<String, Integer> progressSink) {
        this.taskId = taskId;
        this.maxModelCalls = maxModelCalls;
        this.maxToolCalls = maxToolCalls;
        this.maxDurationSeconds = maxDurationSeconds;
        this.maxTotalTokens = maxTotalTokens;
        this.deadline = Instant.now().plusSeconds(maxDurationSeconds);
        this.cancellationCheck = cancellationCheck;
        this.progressSink = progressSink;
    }

    public static TaskRuntime start(UUID taskId, int maxModelCalls, int maxToolCalls, int maxDurationSeconds,
            long maxTotalTokens, Runnable cancellationCheck, BiConsumer<String, Integer> progressSink) {
        return new TaskRuntime(taskId, maxModelCalls, maxToolCalls, maxDurationSeconds, maxTotalTokens,
                cancellationCheck, progressSink);
    }

    /** 取消 + 超时检查，任何模型/工具调用前都会执行 */
    public void check() {
        cancellationCheck.run();
        if (Instant.now().isAfter(deadline)) {
            throw new TaskBudgetExceededException("任务执行超时（上限 " + maxDurationSeconds + " 秒）");
        }
    }

    /** 每次 HTTP 尝试都算一次模型调用，重试同样受预算约束 */
    public void beforeModelCall() {
        check();
        int count = modelCalls.incrementAndGet();
        if (count > maxModelCalls) {
            throw new TaskBudgetExceededException("模型调用次数超过上限：" + maxModelCalls);
        }
    }

    public void beforeToolCall() {
        check();
        int count = toolCalls.incrementAndGet();
        if (count > maxToolCalls) {
            throw new TaskBudgetExceededException("工具调用次数超过上限：" + maxToolCalls);
        }
    }

    public void onUsage(String model, Integer prompt, Integer completion) {
        if (model != null && !model.isBlank()) {
            lastModel = model;
        }
        promptTokens.addAndGet(prompt == null ? 0 : prompt);
        completionTokens.addAndGet(completion == null ? 0 : completion);
        long total = promptTokens.get() + completionTokens.get();
        if (maxTotalTokens > 0 && total > maxTotalTokens) {
            throw new TaskBudgetExceededException("token 用量超过上限：" + maxTotalTokens);
        }
    }

    /** 进入新步骤：重置步骤内进度基线；预算计数与截止时间属于整个计划，不重置 */
    public void beginStep() {
        progress.set(0);
    }

    /** 进度只前进不后退 */
    public void report(String message, int percent) {
        int previous = progress.getAndUpdate(current -> Math.max(current, percent));
        if (percent > previous && progressSink != null) {
            progressSink.accept(message, percent);
        }
    }

    public void markDegraded() {
        degraded.set(true);
    }

    public UUID taskId() {
        return taskId;
    }

    public int modelCalls() {
        return modelCalls.get();
    }

    public int toolCalls() {
        return toolCalls.get();
    }

    public int promptTokens() {
        return (int) promptTokens.get();
    }

    public int completionTokens() {
        return (int) completionTokens.get();
    }

    public int totalTokens() {
        return (int) (promptTokens.get() + completionTokens.get());
    }

    public boolean degraded() {
        return degraded.get();
    }

    public String lastModel() {
        return lastModel;
    }
}
