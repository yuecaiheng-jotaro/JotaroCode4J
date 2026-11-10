package com.kama.piagent.agent.runtime;

import lombok.Builder;
import lombok.Value;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

@Value
@Builder
public class AgentLoopRequest {
    List<Message> messages;
    List<AgentTool> tools;
    AssistantStreamer streamer;
    String systemPrompt;
    @Builder.Default
    int maxSteps = 20;
    @Builder.Default
    int modelWindow = 20;
    @Builder.Default
    ToolExecutionMode toolExecution = ToolExecutionMode.SEQUENTIAL;
    Supplier<List<Message>> steeringMessages;
    Supplier<List<Message>> followUpMessages;
    RunControl control;
    Function<BeforeToolCallContext, BeforeToolCallResult> beforeToolCall;
    Function<AfterToolCallContext, AfterToolCallResult> afterToolCall;
    Function<TurnContext, TurnDecision> finishTurn;

    @Value
    public static class BeforeToolCallContext {
        AssistantMessage assistantMessage;
        AssistantMessage.ToolCall toolCall;
        String arguments;
    }

    @Value
    public static class AfterToolCallContext {
        AssistantMessage assistantMessage;
        AssistantMessage.ToolCall toolCall;
        String arguments;
        String result;
        boolean error;
    }

    @Value
    public static class TurnContext {
        AssistantMessage message;
        StopReason stopReason;
        List<Message> messages;
    }
}
