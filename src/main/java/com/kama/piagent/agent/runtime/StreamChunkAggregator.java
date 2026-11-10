package com.kama.piagent.agent.runtime;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把流式分片收成完整的助手文本和工具调用。
 * 文本分片既可能是增量，也可能是截至当前的全文。
 */
public final class StreamChunkAggregator {

    private final StringBuilder text = new StringBuilder();
    private final Map<String, AssistantMessage.ToolCall> toolCalls = new LinkedHashMap<>();
    private String finishReason;

    public String absorbText(String incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return "";
        }
        String current = text.toString();
        if (!current.isEmpty() && incoming.startsWith(current)) {
            String delta = incoming.substring(current.length());
            text.setLength(0);
            text.append(incoming);
            return delta;
        }
        if (!current.isEmpty() && current.endsWith(incoming)) {
            return "";
        }
        text.append(incoming);
        return incoming;
    }

    public void absorbToolCall(AssistantMessage.ToolCall call) {
        if (call == null || call.name() == null || call.name().isBlank()) {
            return;
        }
        String id = call.id() == null || call.id().isBlank() ? call.name() : call.id();
        AssistantMessage.ToolCall previous = toolCalls.get(id);
        if (previous == null) {
            toolCalls.put(id, copy(id, call));
            return;
        }
        toolCalls.put(id, copy(id, new AssistantMessage.ToolCall(
                id,
                call.type() != null ? call.type() : previous.type(),
                call.name(),
                mergeArguments(previous.arguments(), call.arguments())
        )));
    }

    public void absorbFinishReason(String reason) {
        if (reason != null && !reason.isBlank()) {
            this.finishReason = reason;
        }
    }

    public String text() {
        return text.toString();
    }

    public List<AssistantMessage.ToolCall> toolCalls() {
        return new ArrayList<>(toolCalls.values());
    }

    public String finishReason() {
        return finishReason;
    }

    private static AssistantMessage.ToolCall copy(String id, AssistantMessage.ToolCall call) {
        return new AssistantMessage.ToolCall(
                id,
                call.type() == null ? "function" : call.type(),
                call.name(),
                call.arguments() == null ? "" : call.arguments()
        );
    }

    private static String mergeArguments(String previous, String incoming) {
        String oldArgs = previous == null ? "" : previous;
        String newArgs = incoming == null ? "" : incoming;
        if (newArgs.isEmpty()) {
            return oldArgs;
        }
        if (oldArgs.isEmpty() || newArgs.startsWith(oldArgs)) {
            return newArgs;
        }
        if (oldArgs.startsWith(newArgs)) {
            return oldArgs;
        }
        return oldArgs + newArgs;
    }
}
