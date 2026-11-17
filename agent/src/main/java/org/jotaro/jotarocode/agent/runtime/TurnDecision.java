package org.jotaro.jotarocode.agent.runtime;

/**
 * 单轮（Turn）结束钩子（{@link AgentLoopRequest#getFinishTurn()}）返回的后续调度指令枚举。
 *
 * <p>在 {@link AgentLoop} 中的决策优先级：
 * <ul>
 *   <li>{@link #END}：在发送完本轮 {@code TURN_END} 事件后立即结束整个 {@link AgentLoop}，即使本轮还有未完成的工具跟进或队列消息也不再继续。</li>
 *   <li>{@link #CONTINUE}：即使本轮模型没有发起任何工具调用且 {@code steering} / {@code followUp} 队列均为空，也强制开启下一轮模型请求。</li>
 * </ul>
 */
public enum TurnDecision {
    /** 立即结束本次 AgentLoop 运行。 */
    END,
    /** 强制继续下一轮模型调用。 */
    CONTINUE
}
