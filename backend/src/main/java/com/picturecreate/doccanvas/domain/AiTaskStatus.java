package com.picturecreate.doccanvas.domain;

/**
 * AI 任务状态机。
 * 正常流转：CREATED → QUEUED → RUNNING → SUCCEEDED
 * 异常：   RUNNING/QUEUED 可 → ABORTING → ABORTED（用户中断）
 *         任一步异常 → FAILED
 */
public enum AiTaskStatus {
    /** 已创建（未排队） */
    CREATED,
    /** 排队中（等待线程池执行） */
    QUEUED,
    /** 执行中 */
    RUNNING,
    /** 已完成（动作已落库） */
    SUCCEEDED,
    /** 失败（errorMessage 记录原因） */
    FAILED,
    /** 正在中断（已请求，等安全检查点停止） */
    ABORTING,
    /** 已中断 */
    ABORTED
}
