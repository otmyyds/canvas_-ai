package com.picturecreate.doccanvas.service.ai.agent;

import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;

/**
 * 工具上下文读写。
 * AgentExecutionContext 通过 ToolContext 传入，工具从这里取归属与任务预算。
 */
public final class AgentToolContext {

    /** ToolContext 中携带 AgentExecutionContext 的 key */
    public static final String CONTEXT_KEY = "agentContext";

    private AgentToolContext() {
    }

    public static Map<String, Object> of(AgentExecutionContext context) {
        return Map.of(CONTEXT_KEY, context);
    }

    /** 取当前运行上下文；缺失说明工具被错误调用，直接拒绝 */
    public static AgentExecutionContext require(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new IllegalStateException("工具缺少运行上下文");
        }
        Object value = toolContext.getContext().get(CONTEXT_KEY);
        if (!(value instanceof AgentExecutionContext context)) {
            throw new IllegalStateException("工具运行上下文类型错误");
        }
        return context;
    }
}
