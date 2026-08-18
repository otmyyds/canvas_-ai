package com.picturecreate.doccanvas.domain;

/**
 * 卡片类型（决定卡片视觉与语义）。
 * AI 生成常用 SUMMARY/IDEA/QUESTION；SOURCE 表示资料引用卡。
 */
public enum CardType {
    /** 普通笔记 */
    NOTE,
    /** 摘要/总结 */
    SUMMARY,
    /** 想法/点子 */
    IDEA,
    /** 资料引用 */
    SOURCE,
    /** 问题 */
    QUESTION,
    /** 图片卡 */
    IMAGE,
    /** 提示词草稿 */
    PROMPT
}
