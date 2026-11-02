package com.picturecreate.doccanvas.service.ai.llm;

/**
 * 模型服务限流且重试后仍不可用。
 * 专用于 429：同类错误换模型没有意义（同账号同样被限流），直接给用户可读提示。
 */
public class ModelBusyException extends RuntimeException {

    public ModelBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
