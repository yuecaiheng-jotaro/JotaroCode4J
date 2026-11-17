package org.jotaro.jotarocode.agent.runtime;

import org.springframework.ai.tool.ToolCallback;

/**
 * 基于 Spring AI {@link ToolCallback} 的 {@link AgentTool} 适配器实现。
 *
 * <p>在整体架构中的角色：
 * 无论是本地 {@code @Tool} 注解方法（由 {@code ToolCallbacks.from(contributor)} 解析生成）还是远程 MCP 工具，
 * 都会被包装成 {@code CallbackAgentTool}。模型请求阶段通过 {@link #callback()} 暴露工具参数定义，
 * 工具执行阶段通过 {@link #execute(String)} 真正触发 {@link ToolCallback#call(String)}。
 */
public final class CallbackAgentTool implements AgentTool {

    /** 底层 Spring AI 工具回调对象。 */
    private final ToolCallback callback;
    /** 缓存的工具名称（取自 {@code callback.getToolDefinition().name()}）。 */
    private final String name;
    /** 该工具的执行模式偏好（顺序或并行）。 */
    private final ToolExecutionMode executionMode;

    /**
     * 使用默认执行模式（{@code null}，即跟随全局配置）包装一个 {@link ToolCallback}。
     *
     * @param callback Spring AI 工具回调
     */
    public CallbackAgentTool(ToolCallback callback) {
        this(callback, null);
    }

    /**
     * 使用指定的执行模式包装一个 {@link ToolCallback}。
     *
     * @param callback      Spring AI 工具回调
     * @param executionMode 偏好的工具执行模式（如写文件/执行命令工具会指定 {@link ToolExecutionMode#SEQUENTIAL}）
     */
    public CallbackAgentTool(ToolCallback callback, ToolExecutionMode executionMode) {
        this.callback = callback;
        this.name = callback.getToolDefinition().name();
        this.executionMode = executionMode;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ToolExecutionMode executionMode() {
        return executionMode;
    }

    @Override
    public ToolCallback callback() {
        return callback;
    }

    /**
     * 调用底层 {@link ToolCallback#call(String)} 执行工具方法，并将 {@code null} 返回值兜底转换为空字符串。
     *
     * @param arguments 模型传入的 JSON 参数字符串
     * @return 工具输出字符串（永不为 {@code null}）
     */
    @Override
    public String execute(String arguments) {
        String result = callback.call(arguments);
        return result == null ? "" : result;
    }
}
