package org.jotaro.jotarocode.agent.runtime;

/**
 * 子代理执行过程中的一步。终端据此嵌套显示，而不是等整个工具结束再吐出一大段结果。
 *
 * @param id     这一次子代理调用的编号，并行时用来分开
 * @param agent  角色名，例如 explore
 * @param task   交给它的任务
 * @param kind   这一步是什么
 * @param detail 工具名和参数、增量文本，或最终结果
 */
public record SubagentActivity(String id, String agent, String task, Kind kind, String detail) {

    public enum Kind {
        STARTED,
        TOOL,
        TOOL_RESULT,
        TEXT,
        THINKING,
        FINISHED,
        FAILED
    }
}
