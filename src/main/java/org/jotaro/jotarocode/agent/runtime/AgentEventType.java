package org.jotaro.jotarocode.agent.runtime;

/**
 * 一次运行的事件顺序：AGENT_START，然后若干 TURN_START → 消息流 → 工具开始/结束 → TURN_END，最后 AGENT_END。
 * MESSAGE_UPDATE 的文本是增量；MESSAGE_END 带完整消息。
 */
public enum AgentEventType {
    AGENT_START,
    AGENT_END,
    TURN_START,
    TURN_END,
    MESSAGE_START,
    MESSAGE_UPDATE,
    MESSAGE_END,
    TOOL_EXECUTION_START,
    TOOL_EXECUTION_END
}
