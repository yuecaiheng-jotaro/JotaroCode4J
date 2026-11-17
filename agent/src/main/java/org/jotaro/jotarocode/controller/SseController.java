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
 * 会话事件流（Server-Sent Events）控制器。
 * <p>
 * 每个会话维护一条长连接通道：连接建立时立即推送一条名为 {@code init} 的握手事件，
 * 随后 Agent 运行期间产生的所有状态、流式增量与工具调用事件均封装为名为 {@code message}、
 * 载荷为 {@link org.jotaro.jotarocode.model.dto.AgentEventView} JSON 的 SSE 事件实时下发给前端。
 */
@RestController
@RequestMapping("/api")
public class SseController {

    /** SSE 连接注册与消息推送服务。 */
    @Resource
    private SseService sseService;

    /**
     * 订阅指定会话的实时事件流。
     *
     * @param sessionId 会话 ID
     * @return 绑定到该会话的 {@link SseEmitter}
     */
    @GetMapping(value = "/sessions/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String sessionId) {
        return sseService.connect(sessionId);
    }
}
