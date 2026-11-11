package org.jotaro.jotarocode.agent.runtime;

import lombok.Builder;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

/**
 * 循环抛给订阅者的一条事件。字段按类型使用：文本增量看 {@code textDelta}，
 * 思考增量同样放在 {@code textDelta}，并用 {@code reasoning} 标出来。
 * 工具开始看 {@code toolName} 和 {@code arguments}，工具结束看 {@code result}。
 */
@Data
@Builder
public class AgentEvent {
    private AgentEventType type;
    private Message message;
    private String textDelta;
    private boolean reasoning;
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
