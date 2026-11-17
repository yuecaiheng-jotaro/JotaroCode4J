package org.jotaro.jotarocode.agent.runtime;

/**
 * 单轮大模型响应停止生成的原因枚举。
 *
 * <p>在调用链中的关键分支作用：
 * <ul>
 *   <li>{@link #STOP}：模型自然结束回答且未发起工具调用，若队列无待处理消息则本轮后结束循环。</li>
 *   <li>{@link #TOOL_CALLS}：模型请求调用一个或多个工具，交由 {@link ToolBatchExecutor} 执行后再开启下一轮。</li>
 *   <li>{@link #LENGTH}：模型输出因达到最大 Token 限制被截断。此时若带有 ToolCall，其 JSON 参数往往是残缺的，
 *       {@link ToolBatchExecutor} 不会真正执行这些工具，而是直接构造“未执行：参数可能不完整”的错误 ToolResponse 让模型在下一轮重试。</li>
 *   <li>{@link #ERROR} / {@link #ABORTED}：调用异常或被用户取消，{@link AgentLoop} 将直接终止循环。</li>
 * </ul>
 */
public enum StopReason {
    /** 模型自然完成文本输出，无工具调用。 */
    STOP,
    /** 模型发起了一个或多个工具调用。 */
    TOOL_CALLS,
    /** 模型输出达到 max_tokens 长度上限被截断。 */
    LENGTH,
    /** 模型请求过程发生异常。 */
    ERROR,
    /** 用户在流式生成期间主动取消（Ctrl+C / abort）。 */
    ABORTED
}
