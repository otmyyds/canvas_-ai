package com.picturecreate.doccanvas.service;

/**
 * 每日 AI 配额用尽。
 * 由 ErrorHandler 映射为 HTTP 429，文案直接展示给用户，必须是中文且说明是哪一项配额。
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
