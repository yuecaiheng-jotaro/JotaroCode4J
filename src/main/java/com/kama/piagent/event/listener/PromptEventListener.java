package com.kama.piagent.event.listener;

import com.kama.piagent.event.PromptEvent;
import com.kama.piagent.service.AgentSessionService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
@Slf4j
public class PromptEventListener {

    private final AgentSessionService agentSessionService;

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
