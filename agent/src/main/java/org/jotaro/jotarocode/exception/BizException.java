package org.jotaro.jotarocode.exception;

import lombok.Getter;

/**
 * 面向调用方的可预期业务异常（如会话不存在、消息内容为空、运行中不允许重置或切换模型等）。
 * <p>
 * 由 {@link GlobalExceptionHandler} 统一拦截并转换为前端可读的错误响应，
 * 请勿用它包装底层大模型供应商或未知的系统级故障。
 */
@Getter
public class BizException extends RuntimeException {

    /** 业务状态码，默认固定为 400。 */
    private final int code;

    /**
     * 使用可读的业务错误提示构造异常。
     *
     * @param message 直接展示给用户或调用方的错误原因
     */
    public BizException(String message) {
        super(message);
        this.code = 400;
    }
}
