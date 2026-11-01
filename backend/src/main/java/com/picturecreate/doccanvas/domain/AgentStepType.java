package com.picturecreate.doccanvas.domain;

/**
 * Agent 步骤类型。
 * agent_steps 只追加、不更新，用于审计模型调用、工具调用、委派和最终落库。
 */
public enum AgentStepType {
    /** 模型调用 */
    MODEL_CALL,
    /** 工具调用 */
    TOOL_CALL,
    /** 委派分支或子任务 */
    DELEGATE,
    /** 语义审查 */
    CRITIC,
    /** 合并分支结果 */
    MERGE,
    /** 动作应用落库 */
    APPLY
}
