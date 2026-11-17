package org.jotaro.jotarocode.agent.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 基于 Spring AI {@link ChatClient} 的大模型流式调用实现。
 *
 * <p>核心设计考量：
 * <ul>
 *   <li><b>禁用 Spring AI 内部自动工具执行（{@code internalToolExecutionEnabled(false)}）</b>：
 *       如果开启默认自动执行，Spring AI 会在流式调用内部自行循环调完所有工具才返回最终文本，
 *       外部既拿不到中间的 {@code TOOL_EXECUTION_START/END} 事件，也无法做前置权限确认（{@code beforeToolCall}）、
 *       并行/顺序批次调度、中途插入 {@code steering} 消息或响应 {@code terminate} 工具。
 *       关闭后，工具的 JSON Schema 依然通过 {@code toolCallbacks} 发给模型，但模型一旦决定调用工具，会直接以含 {@code toolCalls} 的
 *       {@link AssistantMessage} 结束当前流，把工具执行权完全交回 {@link AgentLoop}。</li>
 *   <li><b>流式分片聚合</b>：借助 {@link StreamChunkAggregator} 兼容不同模型厂商（增量追加 vs 全量重发）的分片格式，
 *       并实时拆分出思考过程（{@code reasoningContent}）与正式正文增量。</li>
 * </ul>
 */
@Slf4j
public final class SpringAiAssistantStreamer implements AssistantStreamer {

    /** 绑定了特定厂商与模型的 Spring AI ChatClient。 */
    private final ChatClient chatClient;

    /**
     * 使用指定的 {@link ChatClient} 构造流式调用器。
     *
     * @param chatClient 模型对应的客户端
     */
    public SpringAiAssistantStreamer(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public AssistantTurn stream(List<Message> messages,
                                List<AgentTool> tools,
                                String systemPrompt,
                                RunControl control,
                                Consumer<String> onTextDelta) {
        return stream(messages, tools, systemPrompt, control, onTextDelta, ignored -> {
        });
    }

    /**
     * 向大模型发起流式请求，阻塞等待流结束（或被 {@link RunControl} 中止），并将全部分片聚合为 {@link AssistantTurn}。
     */
    @Override
    public AssistantTurn stream(List<Message> messages,
                                List<AgentTool> tools,
                                String systemPrompt,
                                RunControl control,
                                Consumer<String> onTextDelta,
                                Consumer<String> onReasoningDelta) {
        RunControl runControl = control == null ? new RunControl() : control;
        ChatOptions options = DefaultToolCallingChatOptions.builder()
                // 关掉 Spring AI 自动工具执行，让 toolCall 以 AssistantMessage 的形式返回给 AgentLoop，而不是在框架内部直接反射调用
                .internalToolExecutionEnabled(false)
                .build();
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .messages(messages)
                .options(options);
        if (StringUtils.hasText(systemPrompt)) {
            spec = spec.system(systemPrompt);
        }
        // 提取所有工具底层的 ToolCallback，仅用于向模型暴露工具名称、描述和参数 Schema
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
            // 发起 Reactor 流式请求：绑定 Subscription 到 RunControl 以支持随时取消，逐分片交给 aggregator 吸收并触发增量回调
            spec.stream()
                    .chatResponse()
                    .doOnSubscribe(runControl::bind)
                    .doFinally(signal -> runControl.unbind())
                    .takeUntil(response -> runControl.isAborted())
                    .doOnNext(response -> absorb(response, aggregator, onTextDelta, onReasoningDelta))
                    .blockLast();
        } catch (RuntimeException exception) {
            // 若因用户调用 abort() 导致流被取消或中断，返回已收集到的半截正文并标记为 ABORTED
            if (runControl.isAborted()) {
                return AssistantTurn.aborted(aggregator.text());
            }
            log.warn("模型流式调用失败: {}", exception.getMessage());
            return AssistantTurn.error(exception.getMessage() == null ? "模型调用失败" : exception.getMessage());
        }
        if (runControl.isAborted()) {
            return AssistantTurn.aborted(aggregator.text());
        }
        return toTurn(aggregator);
    }

    /**
     * 消费单个流式响应分片 {@link ChatResponse}：依次提取思考内容增量、正文增量、工具调用分片及结束原因。
     */
    private static void absorb(ChatResponse response,
                               StreamChunkAggregator aggregator,
                               Consumer<String> onTextDelta,
                               Consumer<String> onReasoningDelta) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return;
        }
        AssistantMessage output = response.getResult().getOutput();
        // 1. 提取并回调思考过程增量（如 DeepSeek R1 / Qwen 思考模型）
        String reasoningDelta = aggregator.absorbReasoning(reasoningText(response.getResult()));
        if (!reasoningDelta.isEmpty() && onReasoningDelta != null) {
            onReasoningDelta.accept(reasoningDelta);
        }
        // 2. 提取并回调正式回复正文增量
        String delta = aggregator.absorbText(output.getText());
        if (!delta.isEmpty() && onTextDelta != null) {
            onTextDelta.accept(delta);
        }
        // 3. 聚合工具调用分片（兼容按分片流式拼接 arguments 的供应商）
        if (output.getToolCalls() != null) {
            for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
                aggregator.absorbToolCall(toolCall);
            }
        }
        // 4. 记录结束原因（如 stop、tool_calls、length）
        if (response.getResult().getMetadata() != null && response.getResult().getMetadata().getFinishReason() != null) {
            aggregator.absorbFinishReason(response.getResult().getMetadata().getFinishReason());
        }
    }

    /**
     * 从生成结果的元数据中提取推理/思考文本（{@code reasoningContent}）。
     *
     * <p>Qwen、DeepSeek 等思考模型会把思维链放在 {@code output.metadata.get("reasoningContent")} 中，
     * 在思考结束前正式正文 {@code content} 为空字符串。
     *
     * @param generation 单个生成结果分片
     * @return 思考文本分片，不存在时返回空字符串
     */
    static String reasoningText(Generation generation) {
        if (generation == null || generation.getOutput() == null || generation.getOutput().getMetadata() == null) {
            return "";
        }
        Object value = generation.getOutput().getMetadata().get("reasoningContent");
        if (!(value instanceof String text) || text.isEmpty()) {
            return "";
        }
        return text;
    }

    /**
     * 将聚合器收集到的完整正文、工具调用列表和 {@code finishReason} 转换为统一的 {@link AssistantTurn}。
     *
     * <p>边界条件：当 {@code finishReason} 为 {@code "length"}（输出超过最大 token 限制被截断）时，
     * 即使消息里已经解析出了部分 {@code toolCalls}，也必须优先将 {@link StopReason} 设为 {@link StopReason#LENGTH}，
     * 以便下游 {@link ToolBatchExecutor} 拒绝执行残缺不全的工具参数并提示模型重试。
     */
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
