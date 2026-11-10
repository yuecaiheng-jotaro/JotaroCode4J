package com.kama.piagent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.piagent.model.dto.AgentEventView;
import com.kama.piagent.service.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class SseServiceImpl implements SseService {

    private final ConcurrentMap<String, SseEmitter> clients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    @Override
    public SseEmitter connect(String sessionId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        clients.put(sessionId, emitter);
        emitter.onCompletion(() -> clients.remove(sessionId, emitter));
        emitter.onTimeout(() -> clients.remove(sessionId, emitter));
        emitter.onError(error -> clients.remove(sessionId, emitter));
        try {
            emitter.send(SseEmitter.event().name("init").data("connected"));
        } catch (IOException exception) {
            clients.remove(sessionId, emitter);
        }
        return emitter;
    }

    @Override
    public void send(String sessionId, AgentEventView event) {
        SseEmitter emitter = clients.get(sessionId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(objectMapper.writeValueAsString(event)));
        } catch (IOException exception) {
            clients.remove(sessionId, emitter);
            log.warn("推送 SSE 失败, sessionId={}, reason={}", sessionId, exception.getMessage());
        }
    }
}
