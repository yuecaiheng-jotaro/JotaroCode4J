package org.jotaro.jotarocode.agent.runtime;

import lombok.Value;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * 单次大模型调用聚合后的结果值对象。
 *
 * <p>包含拼接完整的 {@link AssistantMessage}、停止原因 {@link StopReason} 以及可选的错误描述。
 * 错误与取消均通过静态工厂方法封装在此对象中返回给 {@link AgentLoop}，不向循环外直接抛异常。
 */
@Value
public class AssistantTurn {
    /** 聚合后的完整助手消息（含正文文本和 ToolCall 列表）。 */
    AssistantMessage message;
    /** 模型本轮结束的原因，指导 {@link AgentLoop} 与 {@link ToolBatchExecutor} 下一步如何行动。 */
    StopReason stopReason;
    /** 当 {@code stopReason} 为 {@link StopReason#ERROR} 或 {@link StopReason#ABORTED} 时的错误/中止说明。 */
    String errorMessage;

    /**
     * 构建正常完成的模型轮次结果。
     * 若未显式指定 {@code stopReason}，则根据消息中是否包含工具调用自动推断为 {@link StopReason#TOOL_CALLS} 或 {@link StopReason#STOP}。
     *
     * @param message    聚合后的助手消息
     * @param stopReason 停止原因（可为 {@code null}）
     * @return {@link AssistantTurn} 实例
     */
    public static AssistantTurn of(AssistantMessage message, StopReason stopReason) {
        StopReason resolved = stopReason;
        if (resolved == null) {
            resolved = message != null && message.hasToolCalls() ? StopReason.TOOL_CALLS : StopReason.STOP;
        }
        return new AssistantTurn(message, resolved, null);
    }

    /**
     * 构建调用失败的模型轮次结果，错误文本同时放入 {@link AssistantMessage} 正文以便通知界面和写入历史。
     *
     * @param message 错误原因描述
     * @return 状态为 {@link StopReason#ERROR} 的 {@link AssistantTurn}
     */
    public static AssistantTurn error(String message) {
        String text = message == null ? "" : message;
        return new AssistantTurn(new AssistantMessage(text), StopReason.ERROR, text);
    }

    /**
     * 构建被用户主动中止的模型轮次结果，保留中止前已流式接收到的半截正文。
     *
     * @param partial 中止前已收到的部分文本
     * @return 状态为 {@link StopReason#ABORTED} 的 {@link AssistantTurn}
     */
    public static AssistantTurn aborted(String partial) {
        String text = partial == null ? "" : partial;
        return new AssistantTurn(new AssistantMessage(text), StopReason.ABORTED, "aborted");
    }
}
