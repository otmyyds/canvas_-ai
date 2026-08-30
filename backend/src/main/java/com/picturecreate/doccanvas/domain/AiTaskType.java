package com.picturecreate.doccanvas.domain;

/**
 * AI 任务类型（决定生成方式 + prompt 约束）。
 * 树型任务走标题大纲 → 确定性解析图形节点树；流程图走单次 JSON；整理/清空不调 LLM。
 */
public enum AiTaskType {
    /** 总结资料 → 图形节点树（单次调用） */
    DOCUMENT_SUMMARY,
    /** 整理画布 → 重新布局已有图形节点（不调 LLM） */
    CANVAS_STRUCTURE,
    /** 清空画布 → 确定性删除全部元素（不调 LLM） */
    CLEAR_CANVAS,
    /** 学习计划 → 图形节点树 */
    STUDY_PLAN,
    /** 产品需求 → 图形节点树 */
    PRODUCT_REQUIREMENT,
    /** 思维导图 → 两段式生成（图形节点 + 曲线连线） */
    GENERATE_MINDMAP,
    /** 流程图 → 单次调用（图形节点 + 连线） */
    GENERATE_FLOWCHART,
    /** 文生图 → 调用百炼文生图接口生成图片资产 */
    GENERATE_IMAGE,
    /** 编辑选中的画布元素 → 修改/丰富/添加分支/删除（单次调用，针对选中图形节点） */
    EDIT_ELEMENT
}
