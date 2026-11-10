package com.kama.piagent.agent.tools;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class ClockTool implements Tool {

    @Override
    public String getName() {
        return "currentTime";
    }

    @Override
    public String getDescription() {
        return "返回当前时间";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(name = "currentTime", description = "返回当前本地时间")
    public String currentTime() {
        return OffsetDateTime.now().toString();
    }
}
