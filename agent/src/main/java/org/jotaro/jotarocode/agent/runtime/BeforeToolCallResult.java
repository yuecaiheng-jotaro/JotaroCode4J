package org.jotaro.jotarocode.agent.runtime;

import lombok.Builder;
import lombok.Value;

/**
 * 工具执行前置钩子（{@link AgentLoopRequest#getBeforeToolCall()}）的返回值。
 *
 * <p>典型使用场景：
 * 在 TUI 终端模式下，当模型尝试调用需要用户确认的工具（如 {@code bash}、{@code write}、{@code edit}）时，
 * {@code beforeToolCall} 钩子会暂停并向用户展示确认选项。若用户拒绝，则返回 {@code block = true} 并附上拒绝原因，
 * {@link ToolBatchExecutor} 将跳过该工具的实际执行，直接把 {@link #reason} 作为工具错误结果回传给大模型。
 */
@Value
@Builder
public class BeforeToolCallResult {
    /** 为 {@code true} 时拦截并跳过该工具的实际执行。 */
    boolean block;
    /** 拦截时回填给大模型的拒绝原因说明（若为空则默认回填“工具执行被拦截”）。 */
    String reason;
    /** 拦截后是否同时要求在当前轮结束后终止整个 ReAct 循环。 */
    boolean terminate;
}
