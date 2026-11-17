package org.jotaro.jotarocode.exception;

import org.jotaro.jotarocode.model.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Web 接口全局异常处理器。
 * <p>
 * 将控制器与服务层抛出的异常统一包装为前端可解析的 {@link ApiResponse} JSON 结构。
 * 注意：静态资源缺失（{@link NoResourceFoundException}）仍保留标准 HTTP 404 状态码，
 * 避免浏览器请求不存在的静态路径时被误包装成 HTTP 200 + {@code code=500}。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 处理可预期的业务异常，将异常消息原样放入 {@link ApiResponse#error(String)} 返回给前端展示。
     *
     * @param exception 业务异常
     * @return 携带具体错误原因的统一响应
     */
    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBizException(BizException exception) {
        return ApiResponse.error(exception.getMessage());
    }

    /**
     * 处理静态资源或未映射路径找不到的问题，直接返回 HTTP 404。
     *
     * @param exception 静态资源未找到异常
     * @return HTTP 404 空响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> handle404(NoResourceFoundException exception) {
        return ResponseEntity.notFound().build();
    }

    /**
     * 兜底处理未预期的系统异常，记录错误堆栈日志并向前端返回通用提示。
     *
     * @param exception 未知异常
     * @return 通用服务器内部错误响应
     */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception exception) {
        log.error("服务器内部错误", exception);
        return ApiResponse.error("服务器内部错误");
    }
}
