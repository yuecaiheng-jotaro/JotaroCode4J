package com.kama.piagent.agent.runtime;

import org.springframework.ai.tool.ToolCallback;

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
