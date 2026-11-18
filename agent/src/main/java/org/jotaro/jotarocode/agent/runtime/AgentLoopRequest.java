package org.jotaro.jotarocode.agent.runtime;

import lombok.Builder;
import lombok.Value;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 启动一次 {@link AgentLoop#run()} 所需的完整不可变参数与生命周期钩子封装。
 *
 * <p>为什么这样设计：
 * {@link AgentLoop} 不直接依赖任何 Spring 容器配置或 Bean，所有输入都收敛在 {@code AgentLoopRequest} 中。
 * 这样在单元测试时无需启动 Spring 上下文，只需构造假实现即可精确测试循环调度、工具拦截、并发与取消行为。
 */
@Value
@Builder
public class AgentLoopRequest {
    /** 初始会话消息列表快照。 */
    List<Message> messages;
    /** 本次运行允许模型调用的工具列表。 */
    List<AgentTool> tools;
    /** 大模型流式调用器。 */
    AssistantStreamer streamer;
    /** 可选的附加系统提示词（若已在 {@link #messages} 首条放入 {@code SystemMessage}，此处通常为 {@code null}）。 */
    String systemPrompt;
    /** 最大模型调用步数上限，默认 20 步。 */
    @Builder.Default
    int maxSteps = 20;
    /** 每次请求模型时保留的最大历史消息窗口条数，默认 20 条。 */
    @Builder.Default
    int modelWindow = 20;
    /** 同一轮多个工具调用的默认执行模式，默认顺序执行（{@code JotaroAgent} 中会显式传 {@code PARALLEL}）。 */
    @Builder.Default
    ToolExecutionMode toolExecution = ToolExecutionMode.SEQUENTIAL;
    /** 拉取 steering（中途插话）消息的供应器，在每轮工具跑完后调用。 */
    Supplier<List<Message>> steeringMessages;
    /** 拉取 follow-up（后续追问）消息的供应器，在循环即将停止前调用。 */
    Supplier<List<Message>> followUpMessages;
    /** 取消控制器，用于在运行途中中止流式输出和打断工具线程。 */
    RunControl control;
    /** 工具执行前钩子（如 TUI 下对高危工具弹窗确认是否放行）。 */
    Function<BeforeToolCallContext, BeforeToolCallResult> beforeToolCall;
    /** 工具执行后钩子（可改写工具返回值、错误状态或终止标志）。 */
    Function<AfterToolCallContext, AfterToolCallResult> afterToolCall;
    /** 单轮（Turn）结束钩子（可覆盖循环是否继续或立即结束的决策）。 */
    Function<TurnContext, TurnDecision> finishTurn;
    /** 当前会话选中的大模型名称（用于查询该模型支持的最大上下文窗口 Token 数）。 */
    String model;
    /** 可选的上下文压缩器：当当前上下文超过所选大模型最大上下文的 0.8 时，启动子代理按配置比例（默认 70%）压缩上下文。 */
    ContextCompactor compactor;
    /** 当循环内部触发了上下文压缩时的回调，用于将压缩后的消息列表同步回外层 {@code JotaroAgent}。 */
    Consumer<List<Message>> onMessagesCompacted;

    /**
     * 传给 {@link #beforeToolCall} 钩子的上下文信息。
     */
    @Value
    public static class BeforeToolCallContext {
        /** 发起本次工具调用的完整助手消息。 */
        AssistantMessage assistantMessage;
        /** 当前准备执行的单个工具调用描述（含 id、name、arguments）。 */
        AssistantMessage.ToolCall toolCall;
        /** 工具调用的原始 JSON 参数字符串。 */
        String arguments;
    }

    /**
     * 传给 {@link #afterToolCall} 钩子的上下文信息。
     */
    @Value
    public static class AfterToolCallContext {
        /** 发起本次工具调用的完整助手消息。 */
        AssistantMessage assistantMessage;
        /** 刚刚执行完毕的工具调用描述。 */
        AssistantMessage.ToolCall toolCall;
        /** 工具调用的原始 JSON 参数字符串。 */
        String arguments;
        /** 工具执行产出的原始结果文本或异常信息。 */
        String result;
        /** 工具执行过程中是否抛出了异常。 */
        boolean error;
    }

    /**
     * 传给 {@link #finishTurn} 钩子的单轮结束上下文信息。
     */
    @Value
    public static class TurnContext {
        /** 本轮模型产出的助手消息。 */
        AssistantMessage message;
        /** 本轮模型停止生成的原因。 */
        StopReason stopReason;
        /** 截至本轮结束时的完整消息历史只读副本。 */
        List<Message> messages;
    }
}
