package org.jotaro.jotarocode.agent.tools;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 时钟工具（{@code currentTime}）。
 *
 * <p>向模型提供带时区偏移量的当前本地时间（ISO-8601 格式），仅作为普通信息查询工具，不参与终止 Agent 循环。
 */
@Component
public class ClockTool implements ToolContributor {

    /**
     * 终端状态栏显示的工具短标签。
     */
    @Override
    public String displayLabel() {
        return "Time";
    }

    /**
     * 获取当前系统本地时间（含时区偏移）。
     *
     * @return ISO-8601 格式的当前时间字符串
     */
    @org.springframework.ai.tool.annotation.Tool(name = "currentTime", description = "返回当前本地时间")
    public String currentTime() {
        return OffsetDateTime.now().toString();
    }
}
