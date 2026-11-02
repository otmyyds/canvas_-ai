package com.picturecreate.doccanvas.service.ai.llm;

import java.util.Map;

import org.springframework.ai.tool.ToolCallback;

/**
 * 一次逻辑模型调用的入参。
 * runtime 为 null 表示不参与任务预算；tools 为空表示本次不暴露工具；
 * listener 为 null 表示不记录本次调用的轨迹。
 */
public record LlmRequest(
        LlmOperation operation,
        String system,
        String user,
        ToolCallback[] tools,
        Map<String, Object> toolContext,
        Double temperature,
        TaskRuntime runtime,
        ModelCallListener listener) {

    /** 不带工具的纯文本调用 */
    public static LlmRequest text(LlmOperation operation, String system, String user, TaskRuntime runtime) {
        return new LlmRequest(operation, system, user, null, null, null, runtime, null);
    }
}
