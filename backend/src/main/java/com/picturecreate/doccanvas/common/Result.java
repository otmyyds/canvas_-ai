package com.picturecreate.doccanvas.common;

/**
 * 统一 JSON 响应体。
 *
 * code 与 HTTP 状态码保持一致：成功为 200，失败为对应的 4xx/5xx。
 * 无响应体的 204 接口和二进制下载接口不使用该包装，避免破坏 HTTP 语义。
 */
public record Result<T>(int code, String message, T data) {

    public static final int SUCCESS_CODE = 200;
    public static final String SUCCESS_MESSAGE = "success";

    /** 成功响应：携带业务数据 */
    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    /** 成功响应：没有业务数据（保留 HTTP 200 的响应体） */
    public static Result<Void> success() {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    /** 失败响应：code 取 HTTP 状态码 */
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
