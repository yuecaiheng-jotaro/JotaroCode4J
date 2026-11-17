package org.jotaro.jotarocode.agent.runtime;

/**
 * 同一条助手消息中包含多个工具调用（ToolCall）时的批次执行模式枚举。
 *
 * <p>降级规则：
 * 在 {@link ToolBatchExecutor} 中，只有当全局模式为 {@link #PARALLEL} 且当批次内所有被调工具自身的
 * {@link AgentTool#executionMode()} 均不为 {@link #SEQUENTIAL} 时，才会并发执行；
 * 只要任一工具声明了 {@link #SEQUENTIAL}（如修改文件或执行命令），整批都会退化为按调用顺序串行执行，避免竞态冲突。
 */
public enum ToolExecutionMode {
    /** 顺序串行执行：按模型给出的 ToolCall 顺序在当前线程逐个执行。 */
    SEQUENTIAL,
    /** 并行执行：通过守护线程池并发执行多个工具调用，但最终按原始顺序组装结果。 */
    PARALLEL
}
