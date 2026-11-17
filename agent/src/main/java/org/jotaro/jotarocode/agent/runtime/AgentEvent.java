package org.jotaro.jotarocode.agent.runtime;

import lombok.Builder;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

/**
 * {@link AgentLoop} 在运行期间向订阅者广播的统一事件载体。
 *
 * <p>各事件类型（{@link AgentEventType}）主要读取的字段约定：
 * <ul>
 *   <li>{@link AgentEventType#MESSAGE_START} / {@link AgentEventType#MESSAGE_END}：读取 {@link #message}（以及 {@link #error}、{@link #toolResults}）。</li>
 *   <li>{@link AgentEventType#MESSAGE_UPDATE}：读取增量文本 {@link #textDelta}；若 {@link #reasoning} 为 {@code true}，表示该增量属于思考过程（如 DeepSeek/Qwen 的思维链），否则为正式回复正文。</li>
 *   <li>{@link AgentEventType#TOOL_EXECUTION_START}：读取 {@link #toolCallId}、{@link #toolName} 与 JSON 参数 {@link #arguments}。</li>
 *   <li>{@link AgentEventType#TOOL_EXECUTION_END}：读取 {@link #toolCallId}、{@link #toolName}、执行结果 {@link #result}、是否出错 {@link #error} 以及耗时 {@link #durationMs}。</li>
 *   <li>{@link AgentEventType#TURN_END}：读取本轮助手消息 {@link #message}、全部工具响应 {@link #toolResults} 及错误标记 {@link #error}。</li>
 *   <li>{@link AgentEventType#AGENT_END}：读取 {@link #stepLimitReached} 判断是否因触达最大步数上限而被动停止。</li>
 * </ul>
 */
@Data
@Builder
public class AgentEvent {
    /** 事件类型。 */
    private AgentEventType type;
    /** 关联的完整 Spring AI 消息对象（在 MESSAGE_START、MESSAGE_END、TURN_END 时携带）。 */
    private Message message;
    /** 流式输出的文本增量分片（在 MESSAGE_UPDATE 时携带）。 */
    private String textDelta;
    /** 标记当前 {@link #textDelta} 是否为模型的深度思考（Reasoning）增量。 */
    private boolean reasoning;
    /** 工具调用的唯一标识 ID，用于将工具开始与结束事件配对。 */
    private String toolCallId;
    /** 被调用的工具名称。 */
    private String toolName;
    /** 模型传入的工具调用 JSON 参数字符串。 */
    private String arguments;
    /** 单个工具执行完毕后的文本结果或错误信息。 */
    private String result;
    /** 标记当前消息或工具调用是否发生错误。 */
    private Boolean error;
    /** 单个工具执行的耗时（毫秒）。 */
    private Long durationMs;
    /** 本轮批量工具调用汇总的响应列表（在汇总 ToolResponseMessage 的 MESSAGE_END 及 TURN_END 时携带）。 */
    private List<ToolResponseMessage.ToolResponse> toolResults;
    /** 标记本次 AgentLoop 是否因为达到 {@code maxSteps} 步数上限而强制终止。 */
    private boolean stepLimitReached;

    /**
     * 快速构建仅包含事件类型的简单事件（如 {@code AGENT_START}、{@code TURN_START}）。
     *
     * @param type 事件类型
     * @return 事件实例
     */
    public static AgentEvent of(AgentEventType type) {
        return AgentEvent.builder().type(type).build();
    }
}
