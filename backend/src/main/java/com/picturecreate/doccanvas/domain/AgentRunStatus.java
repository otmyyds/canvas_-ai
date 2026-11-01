package com.picturecreate.doccanvas.domain;

/**
 * Agent 运行状态。
 * agent_runs 只做审计记录，不驱动任务状态机；任务状态仍以 ai_tasks 为准。
 */
public enum AgentRunStatus {
    /** 运行中 */
    RUNNING,
    /** 正常结束 */
    SUCCEEDED,
    /** 异常结束 */
    FAILED,
    /** 因用户中断或任务取消而结束 */
    ABORTED
}
