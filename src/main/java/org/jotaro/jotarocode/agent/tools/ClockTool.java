package org.jotaro.jotarocode.agent.tools;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/** 返回当前本地时间。名字是 {@code currentTime}，不参与结束循环。 */
@Component
public class ClockTool {

    @org.springframework.ai.tool.annotation.Tool(name = "currentTime", description = "返回当前本地时间")
    public String currentTime() {
        return OffsetDateTime.now().toString();
    }
}
