package com.picturecreate.doccanvas.domain;

/**
 * AI 任务类型。
 * 生成类任务由模型一次性输出 Markdown 大纲或单份 JSON，程序确定性解析为画布动作；
 * 整理/清空/文生图走确定性路径，不调模型。
 */
public enum AiTaskType {
    /** 总结资料 → 形成结构化节点 */
    DOCUMENT_SUMMARY,
    /** 整理画布 → 重新布局已有图形节点（不调 LLM） */
    CANVAS_STRUCTURE,
    /** 清空画布 → 确定性删除全部元素（不调 LLM） */
    CLEAR_CANVAS,
    /** 学习计划 → 形成结构化节点 */
    STUDY_PLAN,
    /** 产品需求 → 形成结构化节点 */
    PRODUCT_REQUIREMENT,
    /** 思维导图 → 树型节点结构 */
    GENERATE_MINDMAP,
    /** 流程图 → 带分支连线的流程结构 */
    GENERATE_FLOWCHART,
    /** 文生图 → 调用百炼文生图接口生成图片资产 */
    GENERATE_IMAGE,
    /** 编辑选中的画布元素 → 修改/丰富/添加分支/删除 */
    EDIT_ELEMENT,
    /** 计划任务 → 入口 Agent 制定有序步骤，后台逐步执行并逐步检查 */
    AGENT_PLAN
}
