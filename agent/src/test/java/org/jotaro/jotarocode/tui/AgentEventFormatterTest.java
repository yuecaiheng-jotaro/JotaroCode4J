package org.jotaro.jotarocode.tui;

import org.jotaro.jotarocode.agent.runtime.AgentEvent;
import org.jotaro.jotarocode.agent.runtime.AgentEventType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 确认流式增量按换行打印，完整消息不会再打印一遍。 */
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

        assertEquals(List.of(PromptStyle.mark() + "你好", "  世界！"), lines);
        assertEquals("", formatter.livePlain());
    }

    @Test
    void keepsAnUnfinishedLineAboveTheInput() {
        AgentEventFormatter formatter = new AgentEventFormatter();

        assertEquals(List.of(), formatter.format(update("你好")));
        assertEquals("你好", formatter.livePlain());
        assertEquals(false, formatter.liveThinking());
        assertEquals(List.of(PromptStyle.mark() + "你好"), formatter.format(update("\n")));
        assertEquals("", formatter.livePlain());
    }

    @Test
    void printsThinkingAboveTheAnswerWithAGap() {
        AgentEventFormatter formatter = new AgentEventFormatter();
        List<String> lines = new ArrayList<>();
        lines.addAll(formatter.format(reasoning("先看一下\n")));
        lines.addAll(formatter.format(update("答案")));
        lines.addAll(formatter.format(AgentEvent.builder()
                .type(AgentEventType.MESSAGE_END)
                .message(new AssistantMessage("答案"))
                .build()));

        assertEquals(List.of(
                PromptStyle.thinking("✻ 先看一下"),
                "",
                PromptStyle.mark() + "答案"
        ), lines);
    }

    @Test
    void printsALongThoughtBeforeTheLineEnds() {
        String chunk = "a".repeat(48);
        AgentEventFormatter formatter = new AgentEventFormatter();

        assertEquals(List.of(PromptStyle.thinking("✻ " + chunk)),
                formatter.format(reasoning(chunk)));
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

        assertEquals(List.of(
                PromptStyle.tool("  readFile a.txt"),
                PromptStyle.dim("  ⎿  content")
        ), lines);
    }

    private static AgentEvent update(String delta) {
        return AgentEvent.builder().type(AgentEventType.MESSAGE_UPDATE).textDelta(delta).build();
    }

    private static AgentEvent reasoning(String delta) {
        return AgentEvent.builder()
                .type(AgentEventType.MESSAGE_UPDATE)
                .reasoning(true)
                .textDelta(delta)
                .build();
    }
}
