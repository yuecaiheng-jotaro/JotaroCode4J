package com.kama.piagent.agent.runtime;

import org.springframework.ai.tool.ToolCallback;

/**
 * 运行时工具。模型侧仍然使用 Spring AI 的 ToolCallback 声明参数，
 * 执行由 Agent 循环接管，以便插入钩子、顺序控制和提前结束。
 */
public interface AgentTool {

    String name();

    default ToolExecutionMode executionMode() {
        return null;
    }

    default ToolCallback callback() {
        return null;
    }

    String execute(String arguments);
}
