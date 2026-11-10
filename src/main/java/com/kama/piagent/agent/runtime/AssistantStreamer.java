package com.kama.piagent.agent.runtime;

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
}
