package org.jotaro.jotarocode.event.listener;

import org.jotaro.jotarocode.event.PromptEvent;
import org.jotaro.jotarocode.service.AgentSessionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * {@code @Async} 让 {@code handle} 离开 HTTP 线程。这里吞掉运行期异常并打日志，
 * 否则异步线程里的异常只会变成未捕获异常，页面看不到。
 */
@Component
@Slf4j
public class PromptEventListener {

    @Resource
    private AgentSessionService agentSessionService;

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
