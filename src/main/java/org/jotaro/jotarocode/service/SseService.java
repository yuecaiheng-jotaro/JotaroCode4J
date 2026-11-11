package org.jotaro.jotarocode.service;

import org.jotaro.jotarocode.model.dto.AgentEventView;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 按 sessionId 保存一条 SSE 连接。没有连接时 {@code send} 直接丢掉事件。 */
public interface SseService {
    SseEmitter connect(String sessionId);

    void send(String sessionId, AgentEventView event);
}
