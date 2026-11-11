package org.jotaro.jotarocode.agent.runtime;

import org.springframework.ai.tool.ToolCallback;

/** 把一个 Spring AI {@code ToolCallback} 包成循环可执行的工具。{@code execute} 才是真正调用 Java 方法的地方。 */
public final class CallbackAgentTool implements AgentTool {

    private final ToolCallback callback;
    private final String name;

    public CallbackAgentTool(ToolCallback callback) {
        this.callback = callback;
        this.name = callback.getToolDefinition().name();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ToolCallback callback() {
        return callback;
    }

    @Override
    public String execute(String arguments) {
        String result = callback.call(arguments);
        return result == null ? "" : result;
    }
}
