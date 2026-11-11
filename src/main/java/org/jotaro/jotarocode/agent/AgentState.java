package org.jotaro.jotarocode.agent;

/** 会话对外可见的状态。页面主要看事件，不直接读这个枚举。 */
public enum AgentState {
    IDLE,
    THINKING,
    EXECUTING,
    FINISHED,
    ERROR
}
