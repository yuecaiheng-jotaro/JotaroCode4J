package com.kama.piagent.service;

import com.kama.piagent.model.dto.AgentEventView;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface SseService {
    SseEmitter connect(String sessionId);

    void send(String sessionId, AgentEventView event);
}
