package org.jotaro.jotarocode.model.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;

/**
 * 统一响应。页面只认 {@code code == 200} 为成功，数据在 {@code data}。
 * 业务错误的 HTTP 状态仍是 200，这是和前端约定的，不要改成 4xx，否则现有页面解析不到 message。
 */
@Data
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;

    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ApiCode.SUCCESS.code, ApiCode.SUCCESS.message, data);
    }

    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(ApiCode.SUCCESS.code, ApiCode.SUCCESS.message, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(ApiCode.ERROR.getCode(), message, null);
    }

    @Getter
    @AllArgsConstructor
    public enum ApiCode {
        SUCCESS(200, "success"),
        ERROR(500, "error");

        private final int code;
        private final String message;
    }
}
