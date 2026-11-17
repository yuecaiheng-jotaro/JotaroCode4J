package org.jotaro.jotarocode.agent.runtime;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式响应分片（Chunk）聚合器，负责将底层流式返回的零散分片组装为完整的助手正文、思考内容、工具调用列表及结束原因。
 *
 * <p>为什么需要专门的聚合算法（跨厂商流式协议兼容）：
 * <ul>
 *   <li><b>文本与思考分片兼容（增量 vs 累积全文）</b>：
 *       有的厂商（如标准 OpenAI 协议）每个 chunk 只发送新增的几个字符（Delta）；
 *       而有的厂商或网关每个 chunk 都会重发从开头截至当前的完整累积文本（Cumulative）。
 *       {@link #absorb(StringBuilder, String)} 通过前缀匹配（{@code incoming.startsWith(current)}）和后缀去重（{@code current.endsWith(incoming)}），
 *       确保无论上游采用哪种模式，都能算出准确的真实增量并避免重复拼接。</li>
 *   <li><b>工具调用参数聚合</b>：
 *       流式返回 ToolCall 时，同一个 {@code id} 的工具调用可能分多个 chunk 到达，且 {@code arguments} 字段既可能是碎片追加，也可能是累积覆盖。
 *       {@link #absorbToolCall(AssistantMessage.ToolCall)} 与 {@link #mergeArguments(String, String)} 按 {@code id} 归并，保证最终得到合法完整的 JSON 参数字符串。</li>
 * </ul>
 */
public final class StreamChunkAggregator {

    /** 累积的正式回复正文缓冲区。 */
    private final StringBuilder text = new StringBuilder();
    /** 累积的思考/推理内容缓冲区（与正式正文隔离，不混入 AssistantMessage.text）。 */
    private final StringBuilder reasoning = new StringBuilder();
    /** 按工具调用 ID 维护的有序 ToolCall 映射表。 */
    private final Map<String, AssistantMessage.ToolCall> toolCalls = new LinkedHashMap<>();
    /** 流结束原因（如 stop、tool_calls、length）。 */
    private String finishReason;

    /**
     * 吸收一段正式回复正文分片，并返回本次实际新增的文本增量。
     *
     * @param incoming 新到达的正文分片
     * @return 本次实际新增的增量字符串（若无新增则返回空串）
     */
    public String absorbText(String incoming) {
        return absorb(text, incoming);
    }

    /**
     * 吸收一段思考（Reasoning）分片，并返回本次实际新增的思考增量。
     * 思考内容和正式正文分开存放，防止把模型的内部思维链污染进最终的 {@link #text()}。
     *
     * @param incoming 新到达的思考内容分片
     * @return 本次实际新增的思考增量字符串
     */
    public String absorbReasoning(String incoming) {
        return absorb(reasoning, incoming);
    }

    /**
     * 获取当前已累积的完整思考文本。
     *
     * @return 完整思考文本
     */
    public String reasoning() {
        return reasoning.toString();
    }

    /**
     * 通用分片吸收与增量提取算法。
     *
     * <p>三种分支情况：
     * <ol>
     *   <li>若 {@code incoming} 以当前已累积的 {@code current} 为前缀，说明上游发送的是“累积全文”，截取多出的尾部作为增量并替换缓冲区。</li>
     *   <li>若 {@code current} 已经以 {@code incoming} 结尾（例如流末尾重复推送了相同分片），则视为空增量忽略。</li>
     *   <li>其他情况视为标准增量分片，直接追加到缓冲区末尾并原样返回。</li>
     * </ol>
     */
    private static String absorb(StringBuilder buffer, String incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return "";
        }
        String current = buffer.toString();
        if (!current.isEmpty() && incoming.startsWith(current)) {
            String delta = incoming.substring(current.length());
            buffer.setLength(0);
            buffer.append(incoming);
            return delta;
        }
        if (!current.isEmpty() && current.endsWith(incoming)) {
            return "";
        }
        buffer.append(incoming);
        return incoming;
    }

    /**
     * 吸收单个工具调用分片。若同一 {@code id}（若无 id 则回退用 {@code name}）已存在，则合并其参数字符串。
     *
     * @param call 流分片中携带的工具调用对象
     */
    public void absorbToolCall(AssistantMessage.ToolCall call) {
        if (call == null || call.name() == null || call.name().isBlank()) {
            return;
        }
        String id = call.id() == null || call.id().isBlank() ? call.name() : call.id();
        AssistantMessage.ToolCall previous = toolCalls.get(id);
        if (previous == null) {
            toolCalls.put(id, copy(id, call));
            return;
        }
        toolCalls.put(id, copy(id, new AssistantMessage.ToolCall(
                id,
                call.type() != null ? call.type() : previous.type(),
                call.name(),
                mergeArguments(previous.arguments(), call.arguments())
        )));
    }

    /**
     * 记录非空的流结束原因（通常在最后一个流式分片的 metadata 中给出）。
     *
     * @param reason 结束原因标识
     */
    public void absorbFinishReason(String reason) {
        if (reason != null && !reason.isBlank()) {
            this.finishReason = reason;
        }
    }

    /**
     * 获取当前已累积的完整正式回复正文。
     *
     * @return 完整正文字符串
     */
    public String text() {
        return text.toString();
    }

    /**
     * 获取按出现顺序排列的完整工具调用列表。
     *
     * @return 工具调用列表
     */
    public List<AssistantMessage.ToolCall> toolCalls() {
        return new ArrayList<>(toolCalls.values());
    }

    /**
     * 获取流结束原因。
     *
     * @return 结束原因字符串（可能为 {@code null}）
     */
    public String finishReason() {
        return finishReason;
    }

    /**
     * 规范化构造 {@link AssistantMessage.ToolCall}，补齐默认 {@code type = "function"} 和非空 {@code arguments}。
     */
    private static AssistantMessage.ToolCall copy(String id, AssistantMessage.ToolCall call) {
        return new AssistantMessage.ToolCall(
                id,
                call.type() == null ? "function" : call.type(),
                call.name(),
                call.arguments() == null ? "" : call.arguments()
        );
    }

    /**
     * 合并同一工具调用前后到达的参数分片：兼容累积覆盖（前缀包含）与增量拼接两种流式行为。
     */
    private static String mergeArguments(String previous, String incoming) {
        String oldArgs = previous == null ? "" : previous;
        String newArgs = incoming == null ? "" : incoming;
        if (newArgs.isEmpty()) {
            return oldArgs;
        }
        if (oldArgs.isEmpty() || newArgs.startsWith(oldArgs)) {
            return newArgs;
        }
        if (oldArgs.startsWith(newArgs)) {
            return oldArgs;
        }
        return oldArgs + newArgs;
    }
}
