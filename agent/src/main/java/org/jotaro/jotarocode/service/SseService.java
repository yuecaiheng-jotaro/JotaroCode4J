package org.jotaro.jotarocode.service;

import org.jotaro.jotarocode.model.dto.AgentEventView;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events (SSE) 连接管理与实时推送服务接口。
 * <p>
 * 按 {@code sessionId} 维护每个会话最新的前端 SSE 通道；
 * 当会话尚未建立连接或连接已断开时，{@link #send(String, AgentEventView)} 会直接静默忽略事件。
 */
public interface SseService {
    /**
     * 为指定会话建立并注册一条新的 SSE 连接（若已有旧连接则覆盖替换），同时下发 {@code init} 握手事件。
     *
     * @param sessionId 会话 ID
     * @return 新建的 {@link SseEmitter} 实例
     */
    SseEmitter connect(String sessionId);

    /**
     * 向指定会话的活跃 SSE 连接推送一条 Agent 事件视图。
     *
     * @param sessionId 会话 ID
     * @param event     待推送的前端事件视图对象
     */
    void send(String sessionId, AgentEventView event);
}
