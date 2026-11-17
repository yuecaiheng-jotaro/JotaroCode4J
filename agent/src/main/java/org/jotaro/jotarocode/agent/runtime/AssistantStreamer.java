package org.jotaro.jotarocode.agent.runtime;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.function.Consumer;

/**
 * 大模型流式调用的边界接口。
 *
 * <p>设计契约：
 * <ul>
 *   <li>将底层大模型框架（如 Spring AI {@code ChatClient}）与核心循环 {@link AgentLoop} 解耦，方便在测试中注入模拟流。</li>
 *   <li>实现类应当把网络错误、模型报错或用户主动取消（{@link RunControl#isAborted()}）收敛编码进返回的 {@link AssistantTurn}，
 *       避免异常直接击穿主循环。</li>
 * </ul>
 */
public interface AssistantStreamer {

    /**
     * 发起一轮大模型流式生成（仅回调正文增量，忽略思考过程增量）。
     *
     * @param messages     当前可见的上下文消息列表
     * @param tools        允许模型调用的工具列表
     * @param systemPrompt 可选的附加系统提示词
     * @param control      当前运行的取消开关
     * @param onTextDelta  每收到一段正文增量时的回调
     * @return 聚合完成后的单轮模型响应 {@link AssistantTurn}
     */
    AssistantTurn stream(List<Message> messages,
                         List<AgentTool> tools,
                         String systemPrompt,
                         RunControl control,
                         Consumer<String> onTextDelta);

    /**
     * 发起一轮大模型流式生成，同时分别回调正文增量与思考（Reasoning）增量。
     *
     * <p>思考增量单独通过 {@code onReasoningDelta} 回调给前端或 TUI 渲染，不会拼入最终的 {@code AssistantMessage} 正文。
     * 默认实现直接退化调用五参版本（丢弃思考增量），使不需要关心思考过程的测试实现无需重写此方法。
     *
     * @param messages         当前可见的上下文消息列表
     * @param tools            允许模型调用的工具列表
     * @param systemPrompt     可选的附加系统提示词
     * @param control          当前运行的取消开关
     * @param onTextDelta      每收到一段正式回复正文增量时的回调
     * @param onReasoningDelta 每收到一段深度思考内容增量时的回调
     * @return 聚合完成后的单轮模型响应 {@link AssistantTurn}
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
