/**
 * 可预期的业务错误用 {@link org.jotaro.jotarocode.exception.BizException} 抛出，
 * {@link org.jotaro.jotarocode.exception.GlobalExceptionHandler} 把它收成 {@code ApiResponse}。
 * 未预料的异常只记日志，不把堆栈返回给页面。
 */
package org.jotaro.jotarocode.exception;
