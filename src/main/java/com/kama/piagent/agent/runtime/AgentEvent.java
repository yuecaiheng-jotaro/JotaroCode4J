package com.kama.piagent.agent.runtime;

import lombok.Builder;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

@Data
@Builder
public class AgentEvent {
    private AgentEventType type;
    private Message message;
    private String textDelta;
    private String toolCallId;
    private String toolName;
    private String arguments;
    private String result;
    private Boolean error;
    private Long durationMs;
    private List<ToolResponseMessage.ToolResponse> toolResults;
    private boolean stepLimitReached;

    public static AgentEvent of(AgentEventType type) {
        return AgentEvent.builder().type(type).build();
    }
}
