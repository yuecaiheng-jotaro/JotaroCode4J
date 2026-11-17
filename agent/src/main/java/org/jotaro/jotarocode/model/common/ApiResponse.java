package org.jotaro.jotarocode.model.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;

/**
 * Web REST 接口的统一响应包装类。
 * <p>
 * 前端页面约定仅将 {@code code == 200} 视为成功，响应载荷放在 {@code data} 字段中。
 * 业务异常与内部错误在 HTTP 层仍保持 200 状态码，通过 {@code code = 500} 与 {@code message} 向页面传递错误原因，
 * 请勿将业务异常改为 HTTP 4xx/5xx，否则现有前端将无法统一解析错误消息。
 *
 * @param <T> 响应数据载荷类型
 */
@Data
public class ApiResponse<T> {

    /** 业务状态码（200 表示成功，500 表示失败）。 */
    private int code;
    /** 响应提示或错误原因描述。 */
    private String message;
    /** 成功时返回的业务数据载荷。 */
    private T data;

    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 构造携带业务数据的成功响应。
     *
     * @param data 响应数据
     * @param <T>  数据类型
     * @return 成功响应对象
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ApiCode.SUCCESS.code, ApiCode.SUCCESS.message, data);
    }

    /**
     * 构造无返回数据的成功响应。
     *
     * @param <T> 数据类型占位
     * @return 空载荷成功响应对象
     */
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(ApiCode.SUCCESS.code, ApiCode.SUCCESS.message, null);
    }

    /**
     * 构造携带错误提示信息的失败响应。
     *
     * @param message 错误原因描述
     * @param <T>     数据类型占位
     * @return 失败响应对象（{@code code=500}）
     */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(ApiCode.ERROR.getCode(), message, null);
    }

    /**
     * 标准响应状态码枚举。
     */
    @Getter
    @AllArgsConstructor
    public enum ApiCode {
        /** 请求处理成功。 */
        SUCCESS(200, "success"),
        /** 请求处理失败。 */
        ERROR(500, "error");

        /** 状态码数值。 */
        private final int code;
        /** 默认状态描述。 */
        private final String message;
    }
}
