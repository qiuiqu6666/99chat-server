package com.chat99.server.common;

/** App 端统一成功响应包装：{ "code": 0, "message": "ok", "data": ... }。 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }
}
