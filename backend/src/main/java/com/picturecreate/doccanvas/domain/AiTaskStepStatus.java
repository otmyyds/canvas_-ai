package com.picturecreate.doccanvas.domain;

/**
 * AI 任务步骤状态。
 * 每步流转：PENDING → RUNNING → DONE / FAILED / ABORTED；
 * 计划在某一步终止时，后续未开始的步骤统一落 SKIPPED。
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
    ABORTED,
    /** 未执行：前序步骤失败或任务被中断 */
    SKIPPED
}
