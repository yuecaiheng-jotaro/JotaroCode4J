package com.kama.piagent.agent.runtime;

/**
 * 同一条助手消息里多个工具调用的执行方式。
 * 任一工具声明为 SEQUENTIAL 时，整批改为顺序执行。
 */
public enum ToolExecutionMode {
    SEQUENTIAL,
    PARALLEL
}
