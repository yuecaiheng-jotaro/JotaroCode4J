package org.jotaro.jotarocode.controller;

import org.jotaro.jotarocode.service.SseService;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 每个会话一条 Server-Sent Events 连接。事件名是 {@code message}，内容是 {@code AgentEventView} 的 JSON。
 */
@RestController
@RequestMapping("/api")
public class SseController {

    @Resource
    private SseService sseService;

    @GetMapping(value = "/sessions/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String sessionId) {
        return sseService.connect(sessionId);
    }
}
