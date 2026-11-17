package org.jotaro.jotarocode.agent.runtime;

/**
 * 子代理（Subagent）执行过程中的实时动态事件载体。
 *
 * <p>为什么需要此事件：
 * 子代理作为父代理的一次 {@code subagent} 工具调用运行，可能会经历多轮推理与多次文件/搜索工具调用。
 * 通过 {@link SubagentScope} 将 {@code SubagentActivity} 实时回调给 TUI 面板（{@code SubagentPane}），
 * 终端能够以缩进树状结构实时展示每个子代理当前正在调什么工具、输出了什么文本，而不是黑盒等待整个子代理跑完才一次性吐出大段结果。
 *
 * @param id     当次子代理实例的唯一标识（如 {@code explore-1}），在并行运行多个子代理时用于区分归属
 * @param agent  子代理角色名称（如 {@code explore}、{@code general}）
 * @param task   分配给该子代理的原始任务描述
 * @param kind   当前活动节点的细分类型
 * @param detail 活动详情：根据 {@code kind} 不同，分别为任务描述、{@code 工具名\n参数}、工具输出、流式文本增量或最终结论/错误原因
 */
public record SubagentActivity(String id, String agent, String task, Kind kind, String detail) {

    /**
     * 子代理活动阶段枚举。
     */
    public enum Kind {
        /** 子代理实例已启动。 */
        STARTED,
        /** 子代理开始调用某个工具。 */
        TOOL,
        /** 子代理的工具调用返回了结果。 */
        TOOL_RESULT,
        /** 子代理产生了正式回复文本增量。 */
        TEXT,
        /** 子代理正在输出深度思考（Reasoning）内容。 */
        THINKING,
        /** 子代理正常完成任务。 */
        FINISHED,
        /** 子代理执行失败或抛出异常。 */
        FAILED
    }
}
