package com.picturecreate.doccanvas.domain;

/**
 * AI 任务类型（决定生成方式 + prompt 约束）。
 * 前 3 个走单次调用，MINDMAP 走两段式（AI1 写文章→AI2 抽结构）。
 */
public enum AiTaskType {
    /** 总结资料 → 详实内容卡（单次调用） */
    DOCUMENT_SUMMARY,
    /** 整理画布 → 精简结构卡（单次调用） */
    CANVAS_STRUCTURE,
    /** 清空画布 → 确定性删除全部元素（不调 LLM） */
    CLEAR_CANVAS,
    /** 生成图片 → 提示词草稿卡（未实现真实生图） */
    GENERATE_IMAGE,
    /** 编辑图片（未实现） */
    EDIT_IMAGE,
    /** 生成封面（未实现） */
    GENERATE_COVER,
    /** 生成图解（未实现） */
    GENERATE_DIAGRAM_ASSET,
    /** 学习计划 → 详实内容卡 */
    STUDY_PLAN,
    /** 产品需求 → 详实内容卡 */
    PRODUCT_REQUIREMENT,
    /** 思维导图 → 两段式生成（图形节点 + 曲线连线） */
    GENERATE_MINDMAP,
    /** 流程图 → 单次调用（卡片节点 + 连线） */
    GENERATE_FLOWCHART
}
