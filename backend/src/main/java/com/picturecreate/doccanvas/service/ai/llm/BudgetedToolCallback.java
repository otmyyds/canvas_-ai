package com.picturecreate.doccanvas.service.ai.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 工具回调装饰器：每次工具调用前扣减任务预算，调用后把输入输出交给观测接口。
 * Spring AI 的工具循环在框架内部执行，只有包住回调才能让工具预算、轨迹和日志真正生效。
 */
public final class BudgetedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(BudgetedToolCallback.class);

    private final ToolCallback delegate;
    private final TaskRuntime runtime;
    private final ToolCallListener listener;

    public BudgetedToolCallback(ToolCallback delegate, TaskRuntime runtime, ToolCallListener listener) {
        this.delegate = delegate;
        this.runtime = runtime;
        this.listener = listener;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return observe(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return observe(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String observe(String input, ToolInvocation invocation) {
        if (runtime != null) {
            runtime.beforeToolCall();
        }
        String toolName = delegate.getToolDefinition().name();
        long start = System.nanoTime();
        try {
            String output = invocation.call();
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            // 工具输入输出完整打印：模型下一轮看到的就是这份内容，排查时缺一不可
            log.info("""
                    ===== 工具调用 =====
                    工具: {} | 耗时: {}ms
                    ----- 输入 -----
                    {}
                    ----- 输出 -----
                    {}
                    ====================""", toolName, latencyMs, input, output);
            notifyListener(toolName, input, output, start, null);
            return output;
        } catch (RuntimeException exception) {
            log.warn("工具调用失败：{} 耗时={}ms 输入={} 原因={}", toolName,
                    (System.nanoTime() - start) / 1_000_000, input, exception.getMessage());
            notifyListener(toolName, input, null, start, exception.getMessage());
            throw exception;
        }
    }

    private void notifyListener(String toolName, String input, String output, long start, String errorMessage) {
        if (listener == null) {
            return;
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        try {
            listener.onCall(toolName, input, output, latencyMs, errorMessage);
        } catch (RuntimeException ignored) {
            // 轨迹写入失败不能影响工具调用本身
        }
    }

    @FunctionalInterface
    private interface ToolInvocation {
        String call();
    }
}
