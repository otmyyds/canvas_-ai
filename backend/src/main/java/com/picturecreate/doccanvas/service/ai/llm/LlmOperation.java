package com.picturecreate.doccanvas.service.ai.llm;

/**
 * 模型调用场景。
 * 场景决定使用的模型、指标标签和调用方传入的预算；不决定提示词内容。
 */
public enum LlmOperation {
    /** 预检：判断聊天还是任务 */
    PREFLIGHT,
    /** 后台 Agent 主循环 */
    AGENT,
    /** 工具内部的一次性模型调用 */
    TOOL
}
