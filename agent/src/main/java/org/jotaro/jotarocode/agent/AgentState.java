package org.jotaro.jotarocode.agent;

/**
 * 会话（{@link JotaroAgent}）对外可见的生命周期状态枚举。
 *
 * <p>在整体调用链中的角色：
 * <ul>
 *   <li>{@link JotaroAgent} 在接收用户输入和消费 {@link org.jotaro.jotarocode.agent.runtime.AgentLoop} 事件时实时维护该状态。</li>
 *   <li>Web 前端与 TUI 界面主要通过订阅细粒度的 {@link org.jotaro.jotarocode.agent.runtime.AgentEvent} 来驱动 UI 渲染，
 *       而 {@code AgentState} 主要用于会话级状态查询、子代理执行成败判定（如检查子代理是否以 {@link #ERROR} 结束）以及单元测试断言。</li>
 * </ul>
 */
public enum AgentState {
    /** 空闲状态：刚创建或已调用 {@link JotaroAgent#reset()} 重置，尚未开始任何对话轮次。 */
    IDLE,
    /** 思考/生成状态：正在向大模型发起流式请求或开始新一轮 Turn。 */
    THINKING,
    /** 工具执行状态：模型返回了 ToolCall，正在调用外部或内置工具。 */
    EXECUTING,
    /** 正常结束状态：本轮循环在未发生致命错误的情况下完成（包括正常答复完毕、主动中止或达到步数上限）。 */
    FINISHED,
    /** 错误状态：模型调用失败或运行时抛出未捕获异常。 */
    ERROR
}
