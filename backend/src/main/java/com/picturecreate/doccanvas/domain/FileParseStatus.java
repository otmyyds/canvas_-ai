package com.picturecreate.doccanvas.domain;

/**
 * 文件解析状态。
 * 上传后自动解析文本类文件（TXT/MD/PDF/DOCX）：
 * PENDING → PARSING → READY（成功）或 FAILED（失败）
 * 非文本类文件保持 PENDING（不解析）。
 */
public enum FileParseStatus {
    /** 待解析（非文本类或不支持） */
    PENDING,
    /** 解析中 */
    PARSING,
    /** 已解析（textExcerpt 有内容，可作为 AI 上下文） */
    READY,
    /** 解析失败 */
    FAILED
}
