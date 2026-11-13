package org.jotaro.jotarocode.agent.runtime;

import lombok.Builder;
import lombok.Value;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 一次 {@link AgentLoop#run()} 的全部输入。循环不读 Spring 配置，测试时直接填这个对象。
 * {@code beforeToolCall} / {@code afterToolCall} / {@code finishTurn} 是可选钩子。终端会对复杂工具设置 {@code beforeToolCall}。
 */
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
