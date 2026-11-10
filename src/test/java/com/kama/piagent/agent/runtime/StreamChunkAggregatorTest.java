package com.kama.piagent.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
