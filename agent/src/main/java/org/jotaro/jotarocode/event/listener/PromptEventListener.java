package org.jotaro.jotarocode.event.listener;

import org.jotaro.jotarocode.event.PromptEvent;
import org.jotaro.jotarocode.service.AgentSessionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 会话消息事件的异步监听器。
 * <p>
 * 借助 {@code @Async}（使用 {@link org.jotaro.jotarocode.config.AsyncConfig#taskExecutor()} 线程池）
 * 将耗时的 Agent 推理循环与 HTTP 请求线程解耦。
 * 这里显式捕获并记录运行期异常，防止后台线程抛出的异常静默变成未捕获异常而丢失日志。
 */
@Component
@Slf4j
public class PromptEventListener {

    /** 会话服务，负责实际驱动指定会话的 Agent 运行。 */
    @Resource
    private AgentSessionService agentSessionService;

    /**
     * 在异步线程中消费 {@link PromptEvent}，驱动对应会话的 Agent 执行。
     *
     * @param event 待处理的用户消息事件
     */
    @Async
    @EventListener
    public void handle(PromptEvent event) {
        try {
            agentSessionService.run(event);
        } catch (RuntimeException exception) {
            log.error("Agent 运行失败, sessionId={}", event.getSessionId(), exception);
        }
    }
}
