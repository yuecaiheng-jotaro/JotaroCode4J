package com.kama.piagent.tui;

import com.kama.piagent.agent.PiAgent;
import com.kama.piagent.agent.PiAgentFactory;
import com.kama.piagent.config.ChatClientRegistry;
import com.kama.piagent.config.PiAgentProperties;
import lombok.AllArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "piagent.mode", havingValue = "tui")
@AllArgsConstructor
public class PiAgentTuiRunner implements CommandLineRunner {

    private final PiAgentFactory piAgentFactory;
    private final ChatClientRegistry chatClientRegistry;
    private final PiAgentProperties properties;

    @Override
    public void run(String... args) throws Exception {
        PiAgent agent = piAgentFactory.create(properties.getModel(), properties.getSystemPrompt());
        new PiAgentTui(agent, chatClientRegistry).run();
    }
}
