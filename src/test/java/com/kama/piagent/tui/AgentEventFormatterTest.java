package com.kama.piagent.tui;

import com.kama.piagent.agent.runtime.AgentEvent;
import com.kama.piagent.agent.runtime.AgentEventType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentEventFormatterTest {

    @Test
    void splitsStreamingDeltasOnNewlinesAndFlushesTheTail() {
        AgentEventFormatter formatter = new AgentEventFormatter();
        List<String> lines = new ArrayList<>();
        lines.addAll(formatter.format(update("你好\n世界")));
        lines.addAll(formatter.format(update("！")));
        lines.addAll(formatter.format(AgentEvent.builder()
                .type(AgentEventType.MESSAGE_END)
                .message(new AssistantMessage("你好\n世界！"))
                .build()));

        assertEquals(List.of("你好", "世界！"), lines);
    }

    @Test
    void printsToolBoundaries() {
        AgentEventFormatter formatter = new AgentEventFormatter();
        List<String> lines = new ArrayList<>();
        lines.addAll(formatter.format(AgentEvent.builder()
                .type(AgentEventType.TOOL_EXECUTION_START)
                .toolName("readFile")
                .arguments("{\"path\":\"a.txt\"}")
                .build()));
        lines.addAll(formatter.format(AgentEvent.builder()
                .type(AgentEventType.TOOL_EXECUTION_END)
                .toolName("readFile")
                .result("content")
                .error(false)
                .build()));
        lines.addAll(formatter.format(AgentEvent.builder().type(AgentEventType.AGENT_END).build()));

        assertEquals(List.of("→ readFile {\"path\":\"a.txt\"}", "← readFile content", "[完成]"), lines);
    }

    private static AgentEvent update(String delta) {
        return AgentEvent.builder().type(AgentEventType.MESSAGE_UPDATE).textDelta(delta).build();
    }
}
