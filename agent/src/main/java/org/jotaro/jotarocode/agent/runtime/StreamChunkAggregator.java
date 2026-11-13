package org.jotaro.jotarocode.agent.runtime;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把流式分片收成完整的助手文本和工具调用。
 * 有的供应商每个分片是新增的几个字，有的每次都重发截至当前的全文；
 * {@link #absorbText(String)} 用前缀判断，避免全文被重复拼上。
 */
public final class StreamChunkAggregator {

    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final Map<String, AssistantMessage.ToolCall> toolCalls = new LinkedHashMap<>();
    private String finishReason;

    public String absorbText(String incoming) {
        return absorb(text, incoming);
    }

    /**
     * 思考内容和正文分开收。有的模型先把 reasoning 流完，正文才开始，不能拼进 {@link #text()}。
     */
    public String absorbReasoning(String incoming) {
        return absorb(reasoning, incoming);
    }

    public String reasoning() {
        return reasoning.toString();
    }

    private static String absorb(StringBuilder buffer, String incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return "";
        }
        String current = buffer.toString();
        if (!current.isEmpty() && incoming.startsWith(current)) {
            String delta = incoming.substring(current.length());
            buffer.setLength(0);
            buffer.append(incoming);
            return delta;
        }
        if (!current.isEmpty() && current.endsWith(incoming)) {
            return "";
        }
        buffer.append(incoming);
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
