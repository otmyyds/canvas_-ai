package com.picturecreate.doccanvas.domain;

/**
 * AI 任务步骤状态。
 * 每步流转：PENDING → RUNNING → DONE / FAILED / ABORTED
 */
public enum AiTaskStepStatus {
    /** 待执行 */
    PENDING,
    /** 执行中 */
    RUNNING,
    /** 完成 */
    DONE,
    /** 失败 */
    FAILED,
    /** 中断 */
    ABORTED
}
