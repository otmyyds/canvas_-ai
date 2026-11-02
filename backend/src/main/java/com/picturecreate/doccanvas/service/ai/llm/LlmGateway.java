package com.picturecreate.doccanvas.service.ai.llm;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import com.picturecreate.doccanvas.config.AppProperties;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 模型调用的唯一入口。
 * 职责：按操作选模型、有限重试、失败切换降级模型、记录 token 与指标。
 *
 * 语义边界：
 * - 一次 call 是「逻辑调用」；Spring AI 内部工具循环产生的额外 HTTP 请求不计入 modelCalls，
 *   由 maxToolCalls 与任务总时长兜底。
 * - 重试只有这一层：Spring AI 内置重试已关到 1 次，HttpClient 也关闭了自动重试，
 *   否则真实请求数会成倍放大且不计入预算。
 * - 429 是账号级限流，换模型同样被限流，所以只原地短退避重试一次；
 *   仍失败抛 ModelBusyException（中文提示），不进入降级分支。
 * - usage 取最终响应的用量。
 * - 取消与预算超限不参与重试、不触发降级，避免把用户取消当成上游故障。
 */
@Component
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    /** 429 限流后的退避时间：只争取一次机会，长时限流交给用户重试 */
    private static final long RATE_LIMIT_BACKOFF_MS = 1000;

    private final ChatClient chatClient;
    private final LlmModelRouter router;
    private final MeterRegistry meterRegistry;
    private final RetryTemplate retryTemplate;

    public LlmGateway(ChatClient.Builder builder, LlmModelRouter router, AppProperties properties,
            MeterRegistry meterRegistry) {
        this.chatClient = builder.build();
        this.router = router;
        this.meterRegistry = meterRegistry;
        this.retryTemplate = buildRetryTemplate(properties.ai().llm().retry());
    }

    private RetryTemplate buildRetryTemplate(AppProperties.LlmRetry retry) {
        Map<Class<? extends Throwable>, Boolean> retryable = Map.of(
                TransientAiException.class, true,
                ResourceAccessException.class, true);
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                Math.max(1, retry.maxAttempts()), retryable, true, false);
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(retry.initialBackoffMs());
        backOffPolicy.setMultiplier(retry.multiplier());
        backOffPolicy.setMaxInterval(retry.maxBackoffMs());
        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(retryPolicy);
        template.setBackOffPolicy(backOffPolicy);
        return template;
    }

    /**
     * 执行一次逻辑模型调用。可重试失败时切换降级模型；
     * 配置错误、取消、预算超限直接抛出，不降级。
     */
    public LlmResult call(LlmRequest request) {
        String primary = router.modelFor(request.operation());
        try {
            return execute(primary, request, false);
        } catch (TransientAiException | ResourceAccessException exception) {
            String fallback = router.fallbackFor(request.operation());
            if (fallback == null || fallback.isBlank() || fallback.equals(primary)) {
                throw exception;
            }
            log.warn("主模型调用失败，切换降级模型：operation={} from={} to={} error={}",
                    request.operation(), primary, fallback, exception.getMessage());
            if (request.runtime() != null) {
                request.runtime().markDegraded();
            }
            return execute(fallback, request, true);
        }
    }

    private LlmResult execute(String model, LlmRequest request, boolean degraded) {
        try {
            return retryTemplate.execute(context -> doCall(model, request, degraded));
        } catch (NonTransientAiException exception) {
            if (!isRateLimited(exception)) {
                throw exception;
            }
            // 429：同账号换模型也会被限流，原地退避重试一次
            log.warn("模型服务限流，{}ms 后重试：operation={} model={}", RATE_LIMIT_BACKOFF_MS,
                    request.operation(), model);
            sleepBeforeRateLimitRetry();
            try {
                return doCall(model, request, degraded);
            } catch (NonTransientAiException retryException) {
                if (!isRateLimited(retryException)) {
                    throw retryException;
                }
                log.warn("模型服务限流重试仍失败：operation={} model={}", request.operation(), model);
                throw new ModelBusyException("模型服务繁忙，请稍后重试", retryException);
            }
        }
    }

    /**
     * Spring AI 把 4xx 统一包成 NonTransientAiException，只能按响应文案识别 429。
     * 百炼限流返回 "HTTP 429" 与 code=limit_requests。
     */
    private boolean isRateLimited(NonTransientAiException exception) {
        String message = exception.getMessage();
        return message != null && (message.contains("HTTP 429") || message.contains("limit_requests"));
    }

    /** 退避期间被中断则恢复中断标记，交给上层按取消处理 */
    private void sleepBeforeRateLimitRetry() {
        try {
            Thread.sleep(RATE_LIMIT_BACKOFF_MS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private LlmResult doCall(String model, LlmRequest request, boolean degraded) {
        TaskRuntime runtime = request.runtime();
        if (runtime != null) {
            // 每次 HTTP 尝试都算一次模型调用，重试也受预算约束
            runtime.beforeModelCall();
        }
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(model);
        if (request.temperature() != null) {
            options.temperature(request.temperature());
        }
        // 完整打印本次请求：排查提示词问题时，日志必须是唯一可信来源
        log.info("""
                ===== 模型请求 =====
                操作: {} | 模型: {} | 温度: {} | 工具: {}
                ----- system prompt -----
                {}
                ----- user prompt -----
                {}
                ====================""", request.operation(), model, request.temperature(),
                toolNames(request.tools()), request.system(), request.user());
        long start = System.nanoTime();
        try {
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .options(options.build())
                    .system(request.system())
                    .user(request.user());
            ToolCallback[] tools = request.tools();
            if (tools != null && tools.length > 0) {
                spec = spec.toolCallbacks(wrapIfNeeded(tools, runtime));
            }
            if (request.toolContext() != null && !request.toolContext().isEmpty()) {
                spec = spec.toolContext(request.toolContext());
            }
            ChatResponse response = spec.call().chatResponse();
            ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
            Usage usage = metadata == null ? null : metadata.getUsage();
            int prompt = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            int completion = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            if (runtime != null) {
                runtime.onUsage(metadata == null ? null : metadata.getModel(), prompt, completion);
            }
            return toResult(response, request, model, degraded, start, prompt, completion);
        } catch (RuntimeException exception) {
            meterRegistry.counter("ai.llm.failures",
                    "operation", operationTag(request.operation()), "model", model).increment();
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.warn("模型调用异常：operation={} model={} 耗时={}ms 原因={}", request.operation(), model,
                    latencyMs, exception.getMessage());
            // 失败尝试同样要落轨迹，否则数据库里只能看到成功的部分
            notifyModelCall(request, new ModelCallTrace(request.operation(), model, degraded,
                    toolNames(request.tools()), request.system(), request.user(), null, null, 0, 0,
                    latencyMs, exception.getMessage()));
            throw exception;
        }
    }

    private LlmResult toResult(ChatResponse response, LlmRequest request, String requestedModel,
            boolean degraded, long startNanos, int prompt, int completion) {
        LlmOperation operation = request.operation();
        AssistantMessage message = response == null || response.getResult() == null
                ? null
                : response.getResult().getOutput();
        String content = message == null || message.getText() == null ? "" : message.getText();
        ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
        int total = prompt + completion;
        String model = metadata == null || metadata.getModel() == null || metadata.getModel().isBlank()
                ? requestedModel : metadata.getModel();
        long latencyNanos = System.nanoTime() - startNanos;
        String finishReason = response == null || response.getResult() == null
                || response.getResult().getMetadata() == null
                ? ""
                : response.getResult().getMetadata().getFinishReason();
        String tag = operationTag(operation);
        meterRegistry.timer("ai.llm.latency", "operation", tag, "model", model)
                .record(latencyNanos, TimeUnit.NANOSECONDS);
        meterRegistry.counter("ai.llm.calls", "operation", tag, "model", model).increment();
        meterRegistry.counter("ai.llm.tokens", "type", "prompt", "model", model).increment(prompt);
        meterRegistry.counter("ai.llm.tokens", "type", "completion", "model", model).increment(completion);
        if (degraded) {
            meterRegistry.counter("ai.llm.degraded", "operation", tag, "model", model).increment();
        }
        // 完整打印本次响应；工具循环的每一轮都会各打一次，便于还原模型实际看到了什么
        log.info("""
                ===== 模型响应 =====
                操作: {} | 模型: {} | 耗时: {}ms | 用量: prompt={} completion={} | 结束原因: {}
                工具调用: {}
                ----- content -----
                {}
                ====================""", operation, model, TimeUnit.NANOSECONDS.toMillis(latencyNanos),
                prompt, completion, finishReason, toolCalls(message), content);
        notifyModelCall(request, new ModelCallTrace(operation, model, degraded,
                toolNames(request.tools()), request.system(), request.user(), content, finishReason,
                prompt, completion, TimeUnit.NANOSECONDS.toMillis(latencyNanos), null));
        return new LlmResult(content, model, prompt, completion, total,
                TimeUnit.NANOSECONDS.toMillis(latencyNanos), degraded);
    }

    /** 轨迹写入失败只丢审计，不能把成功的模型调用变成失败 */
    private void notifyModelCall(LlmRequest request, ModelCallTrace trace) {
        if (request.listener() == null) {
            return;
        }
        try {
            request.listener().onCall(trace);
        } catch (RuntimeException exception) {
            log.warn("写入模型调用轨迹失败：operation={} model={} 原因={}",
                    trace.operation(), trace.model(), exception.getMessage());
        }
    }

    private String operationTag(LlmOperation operation) {
        return operation.name().toLowerCase(Locale.ROOT);
    }

    /** 请求日志里只列工具名，完整定义由模型侧 tool schema 负责 */
    private String toolNames(ToolCallback[] tools) {
        if (tools == null || tools.length == 0) {
            return "无";
        }
        return Arrays.stream(tools)
                .map(tool -> tool.getToolDefinition().name())
                .collect(Collectors.joining(", "));
    }

    /** 模型本轮请求的工具调用及参数，没有调用时返回“无” */
    private String toolCalls(AssistantMessage message) {
        if (message == null || message.getToolCalls().isEmpty()) {
            return "无";
        }
        return message.getToolCalls().stream()
                .map(call -> call.name() + "(" + call.arguments() + ")")
                .collect(Collectors.joining("; "));
    }

    /** Agent 层已经包装过的回调带着轨迹监听器，这里不能二次包装 */
    private ToolCallback[] wrapIfNeeded(ToolCallback[] tools, TaskRuntime runtime) {
        if (runtime == null) {
            return tools;
        }
        ToolCallback[] wrapped = new ToolCallback[tools.length];
        for (int index = 0; index < tools.length; index++) {
            ToolCallback tool = tools[index];
            wrapped[index] = tool instanceof BudgetedToolCallback ? tool : new BudgetedToolCallback(tool, runtime, null);
        }
        return wrapped;
    }
}
