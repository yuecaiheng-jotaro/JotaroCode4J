package org.jotaro.jotarocode.agent.runtime;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.function.Consumer;

/**
 * 模型调用边界。实现必须把失败编码进 AssistantTurn，不要把请求异常抛出循环。
 */
public interface AssistantStreamer {

    AssistantTurn stream(List<Message> messages,
                         List<AgentTool> tools,
                         String systemPrompt,
                         RunControl control,
                         Consumer<String> onTextDelta);

    /**
     * 思考增量单独回调，不进助手正文。默认实现丢掉思考，旧的流式实现不用改。
     */
    default AssistantTurn stream(List<Message> messages,
                                 List<AgentTool> tools,
                                 String systemPrompt,
                                 RunControl control,
                                 Consumer<String> onTextDelta,
                                 Consumer<String> onReasoningDelta) {
        return stream(messages, tools, systemPrompt, control, onTextDelta);
    }
}
