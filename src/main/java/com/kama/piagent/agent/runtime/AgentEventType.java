package com.kama.piagent.agent.runtime;

/**
 * 一次 Agent 运行的生命周期事件，对应 pi-agent-core 的事件序列。
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
