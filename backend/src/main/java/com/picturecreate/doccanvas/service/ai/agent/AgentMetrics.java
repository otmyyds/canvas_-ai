package com.picturecreate.doccanvas.service.ai.agent;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.domain.AgentRole;
import com.picturecreate.doccanvas.domain.AgentRunStatus;
import com.picturecreate.doccanvas.domain.AgentStepType;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Agent 指标。
 * 标签只用角色、状态和步骤类型，不放 userId/taskId，避免高基数拖垮指标系统。
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 运行开始 */
    public void runStarted(AgentRole role) {
        registry.counter("ai.agent.runs", "role", role.name(), "event", "started").increment();
    }

    /** 运行结束：记录耗时；失败和中断单独计数 */
    public void runFinished(AgentRole role, AgentRunStatus status, Duration duration) {
        registry.counter("ai.agent.runs", "role", role.name(), "event", "finished",
                "status", status.name()).increment();
        registry.timer("ai.agent.run.duration", "role", role.name(), "status", status.name())
                .record(duration == null || duration.isNegative() ? Duration.ZERO : duration);
        if (status != AgentRunStatus.SUCCEEDED) {
            registry.counter("ai.agent.failures", "role", role.name(), "status", status.name()).increment();
        }
    }

    /** 步骤耗时，按步骤类型区分 */
    public void step(AgentStepType type, long latencyMs) {
        registry.timer("ai.agent.steps", "type", type.name())
                .record(Duration.ofMillis(Math.max(0, latencyMs)));
    }

    /** 模型 token 用量 */
    public void tokens(AgentRole role, int promptTokens, int completionTokens) {
        if (promptTokens > 0) {
            registry.counter("ai.agent.tokens", "role", role.name(), "kind", "prompt")
                    .increment(promptTokens);
        }
        if (completionTokens > 0) {
            registry.counter("ai.agent.tokens", "role", role.name(), "kind", "completion")
                    .increment(completionTokens);
        }
    }
}
