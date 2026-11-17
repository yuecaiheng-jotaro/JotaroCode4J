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
 * SSE 连接管理与事件推送服务的内存实现。
 * <p>
 * 每个会话只保留最新建立的一条 {@link SseEmitter} 连接（后连上的会覆盖先连上的），
 * 默认超时时间为 30 分钟。当连接完成、超时或写入异常时，会自动从连接表中移除，防止向已断开的客户端继续写入。
 */
@Service
@Slf4j
public class SseServiceImpl implements SseService {

    /** 会话 ID 到活跃 SSE 连接的并发映射表。 */
    private final ConcurrentMap<String, SseEmitter> clients = new ConcurrentHashMap<>();
    /** Jackson 序列化器，用于将 {@link AgentEventView} 转为 JSON 字符串下发。 */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 建立并登记指定会话的 SSE 通道，立即发送一条 {@code init} 事件确认连接就绪。
     */
    @Override
    public SseEmitter connect(String sessionId) {
        // 设置 30 分钟连接超时
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        clients.put(sessionId, emitter);
        // 按连接实例精确移除，确保旧连接延迟触发关闭回调时不会误删已经替换登记的新连接
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

    /**
     * 将 {@link AgentEventView} 序列化为 JSON，并以事件名 {@code message} 推送给对应会话的客户端。
     */
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
