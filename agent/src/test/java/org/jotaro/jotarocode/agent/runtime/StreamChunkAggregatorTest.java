package org.jotaro.jotarocode.agent.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖增量分片和“每次都是全文”两种流式供应商，确认最终文本不重复。 */
class StreamChunkAggregatorTest {

    @Test
    void appendsDeltasAndPrefersCumulativeSnapshots() {
        StreamChunkAggregator aggregator = new StreamChunkAggregator();

        assertEquals("he", aggregator.absorbText("he"));
        assertEquals("llo", aggregator.absorbText("llo"));
        assertEquals("!", aggregator.absorbText("hello!"));
        assertEquals("", aggregator.absorbText("hello!"));
        assertEquals("hello!", aggregator.text());
    }

    @Test
    void keepsReasoningOutOfTheAnswer() {
        StreamChunkAggregator aggregator = new StreamChunkAggregator();

        assertEquals("想", aggregator.absorbReasoning("想"));
        assertEquals("一下", aggregator.absorbReasoning("一下"));
        assertEquals("", aggregator.absorbReasoning("想一下"));
        assertEquals("答", aggregator.absorbText("答"));

        assertEquals("想一下", aggregator.reasoning());
        assertEquals("答", aggregator.text());
    }

    @Test
    void readsReasoningFromTheChunkMetadata() {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .properties(Map.of("reasoningContent", "先想一下"))
                .build();

        assertEquals("先想一下", SpringAiAssistantStreamer.reasoningText(new Generation(message)));
        assertEquals("", SpringAiAssistantStreamer.reasoningText(new Generation(new AssistantMessage("只有正文"))));
    }

    @Test
    void mergesPartialToolArguments() {
        StreamChunkAggregator aggregator = new StreamChunkAggregator();
        aggregator.absorbToolCall(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                "1", "function", "echo", "{\"t"
        ));
        aggregator.absorbToolCall(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                "1", "function", "echo", "ext\":\"hi\"}"
        ));

        assertEquals(1, aggregator.toolCalls().size());
        assertEquals("{\"text\":\"hi\"}", aggregator.toolCalls().get(0).arguments());
        assertFalse(aggregator.toolCalls().isEmpty());
    }

    @Test
    void replacesArgumentsWhenTheChunkAlreadyContainsThePrefix() {
        StreamChunkAggregator aggregator = new StreamChunkAggregator();
        aggregator.absorbToolCall(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                "1", "function", "echo", "{\"t"
        ));
        aggregator.absorbToolCall(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                "1", "function", "echo", "{\"text\":\"hi\"}"
        ));

        assertEquals("{\"text\":\"hi\"}", aggregator.toolCalls().get(0).arguments());
        assertTrue(aggregator.finishReason() == null);
    }
}
