package org.jotaro.jotarocode.tui;

import org.jotaro.jotarocode.agent.PiAgent;
import org.jotaro.jotarocode.agent.PiAgentFactory;
import org.jotaro.jotarocode.config.ChatClientRegistry;
import org.jotaro.jotarocode.config.PiAgentProperties;
import jakarta.annotation.Resource;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 只在 {@code piagent.mode=tui} 时成为 Bean。Spring 启动完成后调用 {@code run}，阻塞到用户退出。
 */
@Component
@ConditionalOnProperty(name = "piagent.mode", havingValue = "tui")
public class PiAgentTuiRunner implements CommandLineRunner {

    @Resource
    private PiAgentFactory piAgentFactory;
    @Resource
    private ChatClientRegistry chatClientRegistry;
    @Resource
    private PiAgentProperties properties;

    @Override
    public void run(String... args) throws Exception {
        PiAgent agent = piAgentFactory.create(properties.getModel(), properties.getSystemPrompt());
        new PiAgentTui(agent, chatClientRegistry, properties.getModel()).run();
    }
}
