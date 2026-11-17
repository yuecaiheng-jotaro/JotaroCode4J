package org.jotaro.jotarocode.agent.runtime;

/**
 * {@link AgentLoop} 运行周期内发出的事件类型枚举。
 *
 * <p>单次运行的标准事件序列：
 * <pre>
 * AGENT_START
 *   └─ (循环每一轮 Turn)
 *       ├─ TURN_START
 *       ├─ [若有排队的用户消息] MESSAGE_START(User) → MESSAGE_END(User)
 *       ├─ MESSAGE_START(Assistant) → MESSAGE_UPDATE* (思考/正文增量) → MESSAGE_END(Assistant)
 *       ├─ [若包含工具调用]
 *       │   ├─ (每个工具) TOOL_EXECUTION_START → TOOL_EXECUTION_END
 *       │   └─ MESSAGE_END(ToolResponseMessage)
 *       └─ TURN_END
 * AGENT_END
 * </pre>
 */
public enum AgentEventType {
    /** 整次 AgentLoop 运行开始。 */
    AGENT_START,
    /** 整次 AgentLoop 运行结束。 */
    AGENT_END,
    /** 单轮（Turn）开始：准备消费待插入消息并请求一次大模型。 */
    TURN_START,
    /** 单轮（Turn）结束：本轮模型响应及其触发的工具调用已全部处理完毕。 */
    TURN_END,
    /** 一条新消息开始产生（可能是中途插入的用户消息，或即将流式生成的助手消息）。 */
    MESSAGE_START,
    /** 助手消息流式生成中的增量更新（正文增量或思考增量）。 */
    MESSAGE_UPDATE,
    /** 一条完整消息构建完毕（JotaroAgent 会在此时将该消息正式落入会话历史列表）。 */
    MESSAGE_END,
    /** 单个工具调用准备开始执行。 */
    TOOL_EXECUTION_START,
    /** 单个工具调用执行结束（无论成功、报错、被拦截还是被取消）。 */
    TOOL_EXECUTION_END
}
