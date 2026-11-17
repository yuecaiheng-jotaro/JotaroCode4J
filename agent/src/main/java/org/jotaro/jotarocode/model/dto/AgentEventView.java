package org.jotaro.jotarocode.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 经由 SSE（{@code message} 事件）推送给前端的扁平化 Agent 事件视图。
 * <p>
 * 字段语义说明：
 * <ul>
 *   <li>{@code text} 在 {@code MESSAGE_UPDATE} 事件中表示本帧流式增量文本，在 {@code MESSAGE_END} 事件中表示整段完整原文（前端若已累加增量，不应在 {@code MESSAGE_END} 时重复追加）。</li>
 *   <li>{@code done} 仅在整轮 Agent 循环结束（{@code AGENT_END}）时为 {@code true}。</li>
 * </ul>
 */
@Data
@Builder
public class AgentEventView {
    /** 事件类型名称（对应 {@link org.jotaro.jotarocode.agent.runtime.AgentEventType} 枚举名）。 */
    private String type;
    /** 消息角色：{@code assistant}、{@code user}、{@code tool} 或 {@code system}。 */
    private String role;
    /** 流式增量文本（{@code MESSAGE_UPDATE}）或完整消息文本（{@code MESSAGE_END}）。 */
    private String text;
    /** 工具调用唯一 ID。 */
    private String toolCallId;
    /** 工具名称（如 {@code read}、{@code bash} 等）。 */
    private String toolName;
    /** 工具调用入参 JSON 字符串。 */
    private String arguments;
    /** 工具执行结果文本。 */
    private String result;
    /** 工具执行是否发生错误。 */
    private Boolean error;
    /** 本轮 Agent 运行是否已彻底结束（即 {@code AGENT_END}）。 */
    private Boolean done;
    /** 本轮是否因达到最大步数限制（{@code maxSteps}）而停止。 */
    private boolean stepLimitReached;
}
