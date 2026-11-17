package org.jotaro.jotarocode.agent.tools;

import org.springframework.stereotype.Component;

/**
 * 终止循环工具（{@code terminate}）。
 *
 * <p>当模型判断当前用户任务已彻底完成时主动调用该工具。
 * Agent 运行时循环检测到 {@code terminate} 工具调用后，会直接结束当前轮次，不再继续向模型发起下一轮推理请求。
 */
@Component
public class TerminateTool implements ToolContributor {

    /**
     * 终端状态栏显示的工具短标签。
     */
    @Override
    public String displayLabel() {
        return "Done";
    }

    /**
     * 标记当前任务已完成并通知运行时退出推理循环。
     *
     * @return 固定完成提示 {@code "已完成"}
     */
    @org.springframework.ai.tool.annotation.Tool(name = "terminate", description = "当前任务已经完成时调用，用来结束这一轮循环")
    public String terminate() {
        return "已完成";
    }
}
