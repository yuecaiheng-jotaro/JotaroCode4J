package org.jotaro.jotarocode.agent.runtime;

import org.springframework.ai.tool.ToolCallback;

/**
 * 运行时工具统一抽象接口。
 *
 * <p>为什么不让 Spring AI 直接自动执行 {@link ToolCallback}，而是抽象出 {@code AgentTool}：
 * <ul>
 *   <li>模型请求阶段：通过 {@link #callback()} 把 Spring AI 的 {@link ToolCallback}（含名称、描述、JSON Schema）传给大模型，让模型知道有哪些工具可调。</li>
 *   <li>工具执行阶段：在 {@link SpringAiAssistantStreamer} 中关闭了 Spring AI 内部自动调用，转由 {@link ToolBatchExecutor} 调用 {@link #execute(String)}，
 *       从而支持前置审批拦截（{@code beforeToolCall}）、后置结果改写（{@code afterToolCall}）、串并行混合控制（{@link #executionMode()}）、
 *       执行耗时统计以及 {@code terminate} 提前终止循环。</li>
 * </ul>
 */
public interface AgentTool {

    /**
     * 获取工具的唯一名称（与大模型 ToolCall 中的 {@code name} 对应）。
     *
     * @return 工具名称
     */
    String name();

    /**
     * 获取该工具偏好的执行模式。
     * 返回 {@link ToolExecutionMode#SEQUENTIAL} 时（例如文件写入、编辑、Bash 命令），如果同一轮有多个工具调用，整批都会退化为顺序执行。
     *
     * @return 执行模式偏好，默认 {@code null}（跟随全局配置）
     */
    default ToolExecutionMode executionMode() {
        return null;
    }

    /**
     * 获取底层 Spring AI 的 {@link ToolCallback}，用于在向大模型发起请求时暴露工具定义与参数 Schema。
     * 纯测试桩工具可以返回 {@code null}。
     *
     * @return Spring AI 工具回调对象
     */
    default ToolCallback callback() {
        return null;
    }

    /**
     * 根据模型传入的 JSON 参数字符串执行工具逻辑。
     *
     * @param arguments JSON 格式的参数字符串
     * @return 工具执行的文本结果
     */
    String execute(String arguments);
}
