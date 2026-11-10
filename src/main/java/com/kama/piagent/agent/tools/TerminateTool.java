package com.kama.piagent.agent.tools;

import org.springframework.stereotype.Component;

@Component
public class TerminateTool implements Tool {

    @Override
    public String getName() {
        return "terminate";
    }

    @Override
    public String getDescription() {
        return "结束当前 Agent 循环";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(name = "terminate", description = "当前任务已经完成时调用，用来结束这一轮循环")
    public String terminate() {
        return "已完成";
    }
}
