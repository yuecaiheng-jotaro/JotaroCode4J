package org.jotaro.jotarocode.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jotaro.jotarocode.model.dto.AgentEventView;
import org.jotaro.jotarocode.service.SseService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 一个会话只保留最新的一条 SSE 连接，后连上的会替换先连上的。
 * 超时 30 分钟；完成、超时、写失败都会从 Map 里摘掉，避免向已断开的连接继续写。
 */
@Service
@Slf4j
public class SseServiceImpl implements SseService {

    private final ConcurrentMap<String, SseEmitter> clients = new ConcurrentHashMap<>();
    @Resource
    private ObjectMapper objectMapper;

    @Override
    public SseEmitter connect(String sessionId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        clients.put(sessionId, emitter);
        // 按连接实例移除，旧连接结束时不会误删已经登记的新连接。
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
