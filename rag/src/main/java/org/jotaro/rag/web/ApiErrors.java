package org.jotaro.rag.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Web 层全局异常处理器，将业务参数校验抛出的 {@link IllegalArgumentException} 统一转换为前端管理页面可解析的 HTTP 400 JSON 响应。
 */
@RestControllerAdvice
public class ApiErrors {

    /**
     * 捕获非法参数异常并返回 {@code {"error": "具体错误原因"}} 格式的 400 响应。
     *
     * @param exception 参数校验异常
     * @return 包含错误提示信息的 HTTP 400 响应体
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }
}
