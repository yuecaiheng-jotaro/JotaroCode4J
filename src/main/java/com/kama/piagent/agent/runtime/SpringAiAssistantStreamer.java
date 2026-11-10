package com.kama.piagent.agent.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 用 Spring AI ChatClient 的流式接口实现模型调用，并关闭框架内部的自动工具执行。
 */
@Slf4j
public final class SpringAiAssistantStreamer implements AssistantStreamer {

    private final ChatClient chatClient;

    public SpringAiAssistantStreamer(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public AssistantTurn stream(List<Message> messages,
                                List<AgentTool> tools,
                                String systemPrompt,
                                RunControl control,
                                Consumer<String> onTextDelta) {
        RunControl runControl = control == null ? new RunControl() : control;
        var options = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .messages(messages)
                .options(options);
        if (StringUtils.hasText(systemPrompt)) {
            spec = spec.system(systemPrompt);
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        if (tools != null) {
            for (AgentTool tool : tools) {
                if (tool.callback() != null) {
                    callbacks.add(tool.callback());
                }
            }
        }
        if (!callbacks.isEmpty()) {
            spec = spec.toolCallbacks(callbacks.toArray(ToolCallback[]::new));
        }

        StreamChunkAggregator aggregator = new StreamChunkAggregator();
        try {
            spec.stream()
                    .chatResponse()
                    .doOnSubscribe(runControl::bind)
                    .doFinally(signal -> runControl.unbind())
                    .takeUntil(response -> runControl.isAborted())
                    .doOnNext(response -> absorb(response, aggregator, onTextDelta))
                    .blockLast();
        } catch (RuntimeException exception) {
            if (runControl.isAborted()) {
                return AssistantTurn.aborted(aggregator.text());
            }
            log.error("模型流式调用失败", exception);
            return AssistantTurn.error(exception.getMessage() == null ? "模型调用失败" : exception.getMessage());
        }
        if (runControl.isAborted()) {
            return AssistantTurn.aborted(aggregator.text());
        }
        return toTurn(aggregator);
    }

    private static void absorb(ChatResponse response, StreamChunkAggregator aggregator, Consumer<String> onTextDelta) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return;
        }
        AssistantMessage output = response.getResult().getOutput();
        String delta = aggregator.absorbText(output.getText());
        if (!delta.isEmpty() && onTextDelta != null) {
            onTextDelta.accept(delta);
        }
        if (output.getToolCalls() != null) {
            for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
                aggregator.absorbToolCall(toolCall);
            }
        }
        if (response.getResult().getMetadata() != null && response.getResult().getMetadata().getFinishReason() != null) {
            aggregator.absorbFinishReason(response.getResult().getMetadata().getFinishReason());
        }
    }

    private static AssistantTurn toTurn(StreamChunkAggregator aggregator) {
        AssistantMessage.Builder builder = AssistantMessage.builder().content(aggregator.text());
        if (!aggregator.toolCalls().isEmpty()) {
            builder.toolCalls(aggregator.toolCalls());
        }
        AssistantMessage message = builder.build();
        String finishReason = aggregator.finishReason();
        if (finishReason != null && "length".equalsIgnoreCase(finishReason)) {
            return AssistantTurn.of(message, StopReason.LENGTH);
        }
        if (message.hasToolCalls()) {
            return AssistantTurn.of(message, StopReason.TOOL_CALLS);
        }
        return AssistantTurn.of(message, StopReason.STOP);
    }
}
