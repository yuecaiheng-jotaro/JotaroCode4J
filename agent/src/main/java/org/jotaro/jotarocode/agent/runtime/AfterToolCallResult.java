package org.jotaro.jotarocode.agent.runtime;

import lombok.Builder;
import lombok.Value;

/**
 * 工具执行后置钩子（{@link AgentLoopRequest#getAfterToolCall()}）的返回值。
 *
 * <p>在调用链中的作用：
 * 当 {@link ToolBatchExecutor} 完成一次工具实际执行后，若注册了 {@code afterToolCall} 钩子，
 * 可通过返回此对象来按需覆写工具输出文本、错误标记以及是否终止当前 ReAct 循环。
 * 任何为 {@code null} 的字段均表示保留工具原本的执行结果，不做覆盖。
 */
@Value
@Builder
public class AfterToolCallResult {
    /** 非空时替换工具原始返回的字符串内容（例如脱敏、裁剪或补充提示）。 */
    String content;
    /** 非空时覆盖该工具调用是否被判定为错误（{@code true} 表示失败）。 */
    Boolean error;
    /** 非空时覆盖该工具调用结束后的循环终止倾向（{@code true} 表示希望结束循环）。 */
    Boolean terminate;
}
