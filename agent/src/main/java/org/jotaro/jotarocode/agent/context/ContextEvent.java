package org.jotaro.jotarocode.agent.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话上下文事件日志条目（参考 DeepSeek Harness 的 Event Log 模型）。
 * <p>
 * 每次用户提问、助手回复、工具返回、目标锚定或上下文压缩，都会以不可变事件的形式追加写入存储（Redis 或内存），
 * 而不直接覆盖或销毁原始历史。发往大模型的消息列表由 {@link ContextPipeline} 从事件日志动态派生。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextEvent {

    public enum Type {
        /** 系统提示词事件。 */
        SYSTEM,
        /** 用户消息事件。 */
        USER,
        /** 助手消息事件（可携带 toolCalls）。 */
        ASSISTANT,
        /** 工具执行结果事件。 */
        TOOL_RESPONSE,
        /** 上下文压缩检查点事件（记录已压缩截止序号 compactedUpToSeq 与浓缩摘要）。 */
        COMPACTION,
        /** 任务目标锚定事件（防止长对话或多轮压缩后偏离核心目标）。 */
        GOAL
    }

    /** 会话内单调递增的事件序号（从 1 开始）。 */
    private long seq;
    /** 事件类型。 */
    private Type type;
    /** 事件写入时间戳（毫秒）。 */
    private long timestamp;
    /** 正文内容（或压缩摘要 / 目标描述）。 */
    private String text;
    /** 助手消息附带的工具调用列表（仅 {@link Type#ASSISTANT} 有效）。 */
    private List<ToolCallData> toolCalls;
    /** 工具返回消息附带的结果列表（仅 {@link Type#TOOL_RESPONSE} 有效）。 */
    private List<ToolResultData> toolResults;
    /** 压缩检查点覆盖的截止事件序号（仅 {@link Type#COMPACTION} 有效，seq <= 该值的对话事件由本摘要替代）。 */
    private Long compactedUpToSeq;
    /** 压缩前被浓缩的历史消息条数（仅 {@link Type#COMPACTION} 有效）。 */
    private Integer compactedCount;
    /** 本次压缩比例（仅 {@link Type#COMPACTION} 有效）。 */
    private Double compactRatio;
    /** 压缩前估算 Token 数（仅 {@link Type#COMPACTION} 有效）。 */
    private Integer beforeTokens;
    /** 压缩后估算 Token 数（仅 {@link Type#COMPACTION} 有效）。 */
    private Integer afterTokens;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallData {
        private String id;
        private String type;
        private String name;
        private String arguments;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolResultData {
        private String id;
        private String name;
        private String responseData;
    }

    /**
     * 将 Spring AI 的 {@link Message} 转换为可序列化的 {@link ContextEvent}。
     */
    public static ContextEvent fromMessage(long seq, Message message) {
        long now = System.currentTimeMillis();
        if (message instanceof SystemMessage system) {
            return ContextEvent.builder()
                    .seq(seq)
                    .type(Type.SYSTEM)
                    .timestamp(now)
                    .text(system.getText())
                    .build();
        }
        if (message instanceof UserMessage user) {
            return ContextEvent.builder()
                    .seq(seq)
                    .type(Type.USER)
                    .timestamp(now)
                    .text(user.getText())
                    .build();
        }
        if (message instanceof AssistantMessage assistant) {
            List<ToolCallData> calls = null;
            if (assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty()) {
                calls = new ArrayList<>();
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    calls.add(new ToolCallData(call.id(), call.type(), call.name(), call.arguments()));
                }
            }
            return ContextEvent.builder()
                    .seq(seq)
                    .type(Type.ASSISTANT)
                    .timestamp(now)
                    .text(assistant.getText())
                    .toolCalls(calls)
                    .build();
        }
        if (message instanceof ToolResponseMessage toolResponse) {
            List<ToolResultData> results = new ArrayList<>();
            if (toolResponse.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    results.add(new ToolResultData(response.id(), response.name(), response.responseData()));
                }
            }
            return ContextEvent.builder()
                    .seq(seq)
                    .type(Type.TOOL_RESPONSE)
                    .timestamp(now)
                    .toolResults(results)
                    .build();
        }
        return ContextEvent.builder()
                .seq(seq)
                .type(Type.USER)
                .timestamp(now)
                .text(message == null ? "" : message.getText())
                .build();
    }

    /**
     * 将事件还原为 Spring AI 的 {@link Message}。
     */
    public Message toMessage() {
        return switch (type) {
            case SYSTEM -> new SystemMessage(text == null ? "" : text);
            case USER -> new UserMessage(text == null ? "" : text);
            case ASSISTANT -> {
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                    for (ToolCallData call : toolCalls) {
                        calls.add(new AssistantMessage.ToolCall(
                                call.getId(),
                                call.getType() == null ? "function" : call.getType(),
                                call.getName(),
                                call.getArguments()
                        ));
                    }
                    yield AssistantMessage.builder()
                            .content(text == null ? "" : text)
                            .toolCalls(calls)
                            .build();
                }
                yield new AssistantMessage(text == null ? "" : text);
            }
            case TOOL_RESPONSE -> {
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                if (toolResults != null) {
                    for (ToolResultData data : toolResults) {
                        responses.add(new ToolResponseMessage.ToolResponse(
                                data.getId(),
                                data.getName(),
                                data.getResponseData() == null ? "" : data.getResponseData()
                        ));
                    }
                }
                yield ToolResponseMessage.builder().responses(responses).build();
            }
            case COMPACTION -> new UserMessage(text == null ? "" : text);
            case GOAL -> new UserMessage("[当前任务目标]\n" + (text == null ? "" : text));
        };
    }
}
