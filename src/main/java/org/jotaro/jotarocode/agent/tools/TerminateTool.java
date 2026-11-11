package org.jotaro.jotarocode.agent.tools;

import org.springframework.stereotype.Component;

/**
 * 模型认为任务完成时调用。循环看到工具名 {@code terminate} 就不再把结果送回模型。
 */
@Component
public class TerminateTool implements ToolContributor {

    @org.springframework.ai.tool.annotation.Tool(name = "terminate", description = "当前任务已经完成时调用，用来结束这一轮循环")
    public String terminate() {
        return "已完成";
    }
}
