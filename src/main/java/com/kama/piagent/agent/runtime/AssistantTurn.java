package com.kama.piagent.agent.runtime;

import lombok.Value;
import org.springframework.ai.chat.messages.AssistantMessage;

@Value
public class AssistantTurn {
    AssistantMessage message;
    StopReason stopReason;
    String errorMessage;

    public static AssistantTurn of(AssistantMessage message, StopReason stopReason) {
        StopReason resolved = stopReason;
        if (resolved == null) {
            resolved = message != null && message.hasToolCalls() ? StopReason.TOOL_CALLS : StopReason.STOP;
        }
        return new AssistantTurn(message, resolved, null);
    }

    public static AssistantTurn error(String message) {
        String text = message == null ? "" : message;
        return new AssistantTurn(new AssistantMessage(text), StopReason.ERROR, text);
    }

    public static AssistantTurn aborted(String partial) {
        String text = partial == null ? "" : partial;
        return new AssistantTurn(new AssistantMessage(text), StopReason.ABORTED, "aborted");
    }
}
