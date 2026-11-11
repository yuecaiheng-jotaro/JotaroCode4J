package org.jotaro.jotarocode.agent.runtime;

/**
 * 一轮模型响应为什么结束。
 * {@code LENGTH} 表示输出被截断，这时工具参数可能不完整，循环不会真正执行那些调用。
 */
public enum StopReason {
    STOP,
    TOOL_CALLS,
    LENGTH,
    ERROR,
    ABORTED
}
