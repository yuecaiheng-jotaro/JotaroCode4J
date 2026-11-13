package org.jotaro.jotarocode.tui;

import org.jotaro.jotarocode.agent.JotaroAgent;
import org.jotaro.jotarocode.agent.JotaroAgentFactory;
import org.jotaro.jotarocode.config.ChatClientRegistry;
import org.jotaro.jotarocode.config.JotaroAgentProperties;
import jakarta.annotation.Resource;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 只在 {@code jotaro.mode=tui} 时成为 Bean。Spring 启动完成后调用 {@code run}，阻塞到用户退出。
 */
@Component
@ConditionalOnProperty(name = "jotaro.mode", havingValue = "tui")
public class JotaroAgentTuiRunner implements CommandLineRunner {

    @Resource
    private JotaroAgentFactory jotaroAgentFactory;
    @Resource
    private ChatClientRegistry chatClientRegistry;
    @Resource
    private JotaroAgentProperties properties;

    @Override
    public void run(String... args) throws Exception {
        JotaroAgent agent = jotaroAgentFactory.create(properties.getModel(), properties.getSystemPrompt());
        new JotaroAgentTui(agent, chatClientRegistry, properties.getModel()).run();
    }
}
