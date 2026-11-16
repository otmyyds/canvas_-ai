package com.picturecreate.doccanvas.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.common.Result;
import com.picturecreate.doccanvas.service.NotFoundException;
import com.picturecreate.doccanvas.service.QuotaExceededException;
import com.picturecreate.doccanvas.service.ai.llm.ModelBusyException;

@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Result<Void>> notFound(NotFoundException exception) {
        log.warn("资源不存在：{}", exception.getMessage());
        return error(HttpStatus.NOT_FOUND, safeMessage(exception.getMessage(), "资源不存在"));
    }

    @ExceptionHandler(ModelBusyException.class)
    ResponseEntity<Result<Void>> modelBusy(ModelBusyException exception) {
        log.warn("模型服务限流：{}", exception.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "模型服务繁忙，请稍后重试");
    }

    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<Result<Void>> quotaExceeded(QuotaExceededException exception) {
        log.warn("AI 配额用尽：{}", exception.getMessage());
        return error(HttpStatus.TOO_MANY_REQUESTS, safeMessage(exception.getMessage(), "今日 AI 配额已用尽，请明天再试"));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Result<Void>> conflict(IllegalStateException exception) {
        log.warn("操作冲突：{}", exception.getMessage());
        return error(HttpStatus.CONFLICT, safeMessage(exception.getMessage(), "当前操作冲突，请稍后重试"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Result<Void>> badRequest(IllegalArgumentException exception) {
        log.warn("请求参数有误：{}", exception.getMessage());
        return error(HttpStatus.BAD_REQUEST, safeMessage(exception.getMessage(), "请求参数有误"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Result<Void>> validation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage() == null ? "请求参数校验失败" : error.getDefaultMessage())
                .orElse("请求参数校验失败");
        return error(HttpStatus.BAD_REQUEST, toChineseValidation(message));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class
    })
    ResponseEntity<Result<Void>> unreadable() {
        return error(HttpStatus.BAD_REQUEST, "请求体或参数格式不正确");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Result<Void>> tooLarge() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "上传文件过大");
    }

    /** 未匹配到任何接口：默认会落到通用异常里变成 500，这里明确回 404 */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Result<Void>> noResource() {
        return error(HttpStatus.NOT_FOUND, "接口不存在");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Result<Void>> generic(Exception exception) {
        log.error("服务器内部错误", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, safeMessage(exception.getMessage(), "服务器内部错误，请稍后重试"));
    }

    private ResponseEntity<Result<Void>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Result.error(status.value(), message));
    }

    private String safeMessage(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        return toChineseValidation(message);
    }

    private String toChineseValidation(String message) {
        if (message == null || message.isBlank()) {
            return "请求参数校验失败";
        }
        if (message.contains("must not be blank") || message.contains("must not be empty")) {
            return "内容不能为空";
        }
        if (message.contains("must not be null")) {
            return "必填项不能为空";
        }
        return message;
    }
}
