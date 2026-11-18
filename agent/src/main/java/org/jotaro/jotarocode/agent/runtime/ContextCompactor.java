package org.jotaro.jotarocode.agent.runtime;

import lombok.extern.slf4j.Slf4j;
import org.jotaro.jotarocode.agent.AgentState;
import org.jotaro.jotarocode.agent.JotaroAgent;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 上下文压缩器（通过派生独立的 {@code compact} 子代理完成历史消息浓缩）。
 *
 * <p>核心设计机制：
 * <ul>
 *   <li><b>按模型最大上下文的 80% 阈值触发</b>：根据当前选中的大模型（如 {@code deepseek-chat}、{@code glm-4.6}、{@code qwen4-max-preview}）
 *       查询其支持的最大上下文窗口 Token 数；当当前对话估算 Token 数超过该模型最大上下文的 {@code triggerThreshold}（默认 {@code 0.8}，即 80%）时，
 *       自动触发子代理压缩。</li>
 *   <li><b>按可配置比例压缩（默认 70%）</b>：触发压缩时，除去首条 {@link SystemMessage} 外，将前 {@code ratio}（默认 {@code 0.7}，即 70%）
 *       的历史对话切出交给压缩子代理生成高密度摘要，保留最近 {@code 1 - ratio}（默认 30%）的原始对话消息不变。</li>
 *   <li><b>ToolCall / ToolResponse 配对保护</b>：切分边界 {@code splitIndex} 若恰好落在 {@link ToolResponseMessage} 上，
 *       会自动向前调整到发起该调用的 {@link AssistantMessage} 之前，避免剩余消息以孤立的 {@code role=tool} 开头导致大模型 API 校验报错。</li>
 *   <li><b>独立子代理执行</b>：每次压缩都会启动一个无工具、单步（{@code maxSteps = 1}）、拥有专属压缩系统提示词的子 {@link JotaroAgent}，
 *       并通过 {@link SubagentScope} 向外广播 {@code compact} 子代理的活动进度。</li>
 * </ul>
 */
@Slf4j
public final class ContextCompactor {

    /** 默认触发压缩的上下文水位阈值：达到当前模型最大上下文的 80%（0.8）时触发。 */
    public static final double DEFAULT_TRIGGER_THRESHOLD = 0.8;

    /** 默认压缩比例：70%（0.7）。 */
    public static final double DEFAULT_RATIO = 0.7;

    /** 未单独配置时的模型默认最大上下文窗口（64K Tokens）。 */
    public static final int DEFAULT_CONTEXT_LIMIT = 64_000;

    /** 内置模型默认最大上下文窗口表（单位：Token）。 */
    private static final Map<String, Integer> BUILTIN_MODEL_LIMITS = Map.of(
            "deepseek-chat", 64_000,
            "glm-4.6", 128_000,
            "qwen4-max-preview", 128_000
    );

    /** 单条工具输出送入压缩子代理时的最大字符数，避免单条巨型日志撑爆压缩请求。 */
    private static final int MAX_TOOL_OUTPUT_CHARS = 2_000;

    /** 压缩子代理角色名。 */
    private static final String SUBAGENT_NAME = "compact";

    /** 压缩子代理的系统提示词。 */
    private static final String COMPACT_SYSTEM_PROMPT = """
            你是上下文压缩子代理（compact）。你的唯一任务是将给定的历史对话高密度浓缩成一份结构化摘要，供主代理在后续对话中无缝继续工作。
            请严格保留以下核心信息：
            1. 用户的核心目标、需求变更与约束条件；
            2. 已完成的操作、读取/修改/创建的文件路径及关键代码改动；
            3. 工具执行或代码探索得到的关键结论、报错原因与配置信息；
            4. 当前尚未完成或待继续的下一步任务。
            直接输出浓缩后的中文摘要正文，条理清晰，不要寒暄，不要调用任何工具。""";

    /** 压缩子代理实例递增序号，用于 TUI 活动面板标识（如 compact-1）。 */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** 归一化后的压缩比例（范围 0.1 ~ 0.95；<= 0 表示禁用）。 */
    private final double ratio;

    /** 触发自动压缩的上下文水位比例（默认 0.8，即超过模型最大上下文的 80% 时触发）。 */
    private final double triggerThreshold;

    /** 默认兜底的模型最大上下文 Token 数。 */
    private final int defaultContextLimit;

    /** 按模型名配置的最大上下文 Token 数映射表。 */
    private final Map<String, Integer> modelContextLimits;

    public ContextCompactor() {
        this(DEFAULT_RATIO, DEFAULT_TRIGGER_THRESHOLD, DEFAULT_CONTEXT_LIMIT, BUILTIN_MODEL_LIMITS);
    }

    public ContextCompactor(double ratio) {
        this(ratio, DEFAULT_TRIGGER_THRESHOLD, DEFAULT_CONTEXT_LIMIT, BUILTIN_MODEL_LIMITS);
    }

    public ContextCompactor(double ratio,
                            double triggerThreshold,
                            int defaultContextLimit,
                            Map<String, Integer> modelContextLimits) {
        this.ratio = normalizeRatio(ratio);
        this.triggerThreshold = normalizeRatio(triggerThreshold);
        this.defaultContextLimit = defaultContextLimit > 0 ? defaultContextLimit : DEFAULT_CONTEXT_LIMIT;
        Map<String, Integer> merged = new LinkedHashMap<>(BUILTIN_MODEL_LIMITS);
        if (modelContextLimits != null) {
            merged.putAll(modelContextLimits);
        }
        this.modelContextLimits = Map.copyOf(merged);
    }

    public double ratio() {
        return ratio;
    }

    public double triggerThreshold() {
        return triggerThreshold;
    }

    public boolean enabled() {
        return ratio > 0 && triggerThreshold > 0;
    }

    /**
     * 获取指定模型支持的最大上下文窗口 Token 数。
     */
    public int contextLimitFor(String model) {
        if (model != null && !model.isBlank()) {
            Integer limit = modelContextLimits.get(model.trim());
            if (limit != null && limit > 0) {
                return limit;
            }
        }
        return defaultContextLimit;
    }

    /**
     * 判断当前上下文是否已超过所选大模型最大上下文窗口的触发比例（默认 0.8）。
     *
     * @param messages 当前完整上下文消息列表
     * @param model    当前选中的大模型名称
     * @return 若估算 Token 数达到或超过 {@code contextLimitFor(model) * triggerThreshold}，返回 {@code true}
     */
    public boolean shouldCompact(List<Message> messages, String model) {
        if (!enabled() || messages == null || messages.size() < 3) {
            return false;
        }
        int currentTokens = estimateTokens(messages);
        int maxTokens = contextLimitFor(model);
        double threshold = maxTokens * triggerThreshold;
        return currentTokens >= threshold;
    }

    /**
     * 估算消息列表占用的总 Token 数。
     * <p>
     * 针对中英文与代码混合场景的轻量估算规则：
     * 非 ASCII 字符（如中文汉字）按 1 字符 ≈ 1 Token 计算；ASCII 字符（英文/代码）按 4 字符 ≈ 1 Token 计算，
     * 并计入每条消息的角色结构开销及工具调用（{@code toolCalls}）与工具输出（{@code ToolResponse}）的长度。
     */
    public static int estimateTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            total += 4;
            total += estimateTextTokens(message.getText());
            if (message instanceof AssistantMessage assistant && assistant.getToolCalls() != null) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    total += 4;
                    total += estimateTextTokens(call.name());
                    total += estimateTextTokens(call.arguments());
                }
            } else if (message instanceof ToolResponseMessage toolResponse && toolResponse.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    total += 4;
                    total += estimateTextTokens(response.name());
                    total += estimateTextTokens(response.responseData());
                }
            }
        }
        return total;
    }

    /**
     * 估算单段文本的 Token 数。
     */
    public static int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int ascii = 0;
        int nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) <= 127) {
                ascii++;
            } else {
                nonAscii++;
            }
        }
        return nonAscii + (ascii + 3) / 4;
    }

    /**
     * 归一化比例配置：支持填 {@code 0.7}（小数）或 {@code 70}（百分比）。
     * 小于等于 0 时返回 0（表示关闭压缩），其余情况收敛在 {@code [0.1, 0.95]} 之间。
     */
    public static double normalizeRatio(Double raw) {
        if (raw == null || Double.isNaN(raw)) {
            return DEFAULT_RATIO;
        }
        if (raw <= 0) {
            return 0;
        }
        double value = raw > 1.0 && raw <= 100.0 ? raw / 100.0 : raw;
        return Math.max(0.1, Math.min(0.95, value));
    }

    /**
     * 解析用户输入的压缩比例参数（支持 {@code "70%"}、{@code "70"}、{@code "0.7"} 等格式）。
     */
    public static double parseRatio(String input, double fallback) {
        if (input == null || input.isBlank()) {
            return normalizeRatio(fallback);
        }
        String cleaned = input.trim();
        if (cleaned.endsWith("%")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        try {
            return normalizeRatio(Double.parseDouble(cleaned));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("无效的压缩比例: " + input + "（示例: 0.7 或 70%）");
        }
    }

    /**
     * 使用默认配置的压缩比例执行上下文压缩。
     */
    public Result compact(List<Message> messages, AssistantStreamer streamer, RunControl control) {
        return compact(messages, streamer, control, this.ratio);
    }

    /**
     * 启动压缩子代理，将 {@code messages} 中前 {@code targetRatio} 比例的历史对话压缩为摘要消息。
     *
     * @param messages    原始完整消息列表
     * @param streamer    当前会话使用的模型流式调用器
     * @param control     取消控制器
     * @param targetRatio 本次压缩使用的比例（如 0.7 表示压缩前 70% 的历史消息）
     * @return 压缩结果（包含替换后的新消息列表、被压缩的消息条数、前后 Token 数及摘要文本）
     */
    public Result compact(List<Message> messages, AssistantStreamer streamer, RunControl control, double targetRatio) {
        double effectiveRatio = normalizeRatio(targetRatio);
        int beforeTokens = estimateTokens(messages);
        if (messages == null || messages.isEmpty() || streamer == null || effectiveRatio <= 0) {
            List<Message> safe = messages == null ? List.of() : List.copyOf(messages);
            return new Result(safe, 0, safe.size(), safe.size(), beforeTokens, beforeTokens, "", effectiveRatio);
        }

        int convStart = (messages.get(0) instanceof SystemMessage) ? 1 : 0;
        int convCount = messages.size() - convStart;
        // 至少要有 2 条非系统消息才值得切分压缩
        if (convCount < 2) {
            List<Message> safe = List.copyOf(messages);
            return new Result(safe, 0, safe.size(), safe.size(), beforeTokens, beforeTokens, "", effectiveRatio);
        }

        int targetCompactCount = Math.max(1, (int) Math.round(convCount * effectiveRatio));
        // 至少保留最后 1 条消息（例如最新一轮的用户输入或上下文）不被压缩
        int splitIndex = Math.min(messages.size() - 1, convStart + targetCompactCount);

        // 防止切分点落在 ToolResponseMessage 上，拆散 AssistantMessage(toolCalls) 与 ToolResponseMessage
        int adjustedSplit = splitIndex;
        while (adjustedSplit > convStart && messages.get(adjustedSplit) instanceof ToolResponseMessage) {
            adjustedSplit--;
        }
        if (adjustedSplit <= convStart) {
            adjustedSplit = splitIndex;
            while (adjustedSplit < messages.size() && messages.get(adjustedSplit) instanceof ToolResponseMessage) {
                adjustedSplit++;
            }
        }
        if (adjustedSplit <= convStart || adjustedSplit > messages.size()) {
            List<Message> safe = List.copyOf(messages);
            return new Result(safe, 0, safe.size(), safe.size(), beforeTokens, beforeTokens, "", effectiveRatio);
        }

        List<Message> sliceToCompact = messages.subList(convStart, adjustedSplit);
        List<Message> retainedTail = messages.subList(adjustedSplit, messages.size());
        int compactedCount = sliceToCompact.size();
        int percent = (int) Math.round(effectiveRatio * 100);

        String transcript = buildTranscript(sliceToCompact);
        String summary = runCompactionSubagent(transcript, compactedCount, percent, streamer, control);
        if (summary == null || summary.isBlank()) {
            List<Message> safe = List.copyOf(messages);
            return new Result(safe, 0, safe.size(), safe.size(), beforeTokens, beforeTokens, "", effectiveRatio);
        }

        List<Message> compactedMessages = new ArrayList<>();
        if (convStart == 1) {
            compactedMessages.add(messages.get(0));
        }
        UserMessage summaryUserMessage = new UserMessage(
                "[历史上下文压缩摘要（已由子代理压缩前 " + compactedCount + " 条消息，压缩比例 " + percent + "%）]\n" + summary.strip()
        );
        compactedMessages.add(summaryUserMessage);
        // 若保留的尾部第一条仍然是 UserMessage，则补一条简短的 AssistantMessage 保持 user/assistant 角色严格交替
        if (!retainedTail.isEmpty() && retainedTail.get(0) instanceof UserMessage) {
            compactedMessages.add(new AssistantMessage("已记录上述历史上下文摘要。"));
        }
        compactedMessages.addAll(retainedTail);

        List<Message> immutableResult = List.copyOf(compactedMessages);
        int afterTokens = estimateTokens(immutableResult);
        return new Result(
                immutableResult,
                compactedCount,
                messages.size(),
                immutableResult.size(),
                beforeTokens,
                afterTokens,
                summary.strip(),
                effectiveRatio
        );
    }

    /**
     * 派生一个独立的无工具子 {@link JotaroAgent} 执行上下文摘要生成，并向外部广播子代理活动事件。
     */
    private String runCompactionSubagent(String transcript,
                                         int compactedCount,
                                         int percent,
                                         AssistantStreamer streamer,
                                         RunControl parentControl) {
        RunControl effectiveControl = parentControl != null ? parentControl : RunControl.current();
        JotaroAgent subagent = new JotaroAgent(
                COMPACT_SYSTEM_PROMPT,
                List.of(),
                streamer,
                1,
                10,
                ToolExecutionMode.SEQUENTIAL,
                effectiveControl,
                null
        );

        SubagentScope.Context scope = SubagentScope.current();
        Consumer<SubagentActivity> activity = scope == null ? null : scope.activity();
        String id = SUBAGENT_NAME + "-" + SEQUENCE.incrementAndGet();
        String taskTitle = "压缩前 " + percent + "% 上下文（" + compactedCount + " 条消息）";

        StringBuilder reply = new StringBuilder();
        AtomicBoolean streamed = new AtomicBoolean();
        notifyActivity(activity, id, taskTitle, SubagentActivity.Kind.STARTED, taskTitle);

        subagent.subscribe(event -> {
            if (event.getType() == AgentEventType.MESSAGE_UPDATE && event.isReasoning()) {
                notifyActivity(activity, id, taskTitle, SubagentActivity.Kind.THINKING, "");
            }
            if (event.getType() == AgentEventType.MESSAGE_UPDATE && !event.isReasoning()
                    && event.getTextDelta() != null && !event.getTextDelta().isEmpty()) {
                streamed.set(true);
                notifyActivity(activity, id, taskTitle, SubagentActivity.Kind.TEXT, event.getTextDelta());
            }
            if (event.getType() == AgentEventType.MESSAGE_END && event.getMessage() instanceof AssistantMessage assistant) {
                reply.setLength(0);
                if (assistant.getText() != null) {
                    reply.append(assistant.getText());
                }
                if (!streamed.get() && assistant.getText() != null && !assistant.getText().isBlank()) {
                    notifyActivity(activity, id, taskTitle, SubagentActivity.Kind.TEXT, assistant.getText());
                }
            }
        });

        try {
            subagent.prompt("请将以下历史对话浓缩为结构化上下文摘要：\n\n" + transcript);
        } catch (RuntimeException exception) {
            log.warn("上下文压缩子代理执行异常: {}", exception.getMessage());
            notifyActivity(activity, id, taskTitle, SubagentActivity.Kind.FAILED,
                    exception.getMessage() == null ? "上下文压缩失败" : exception.getMessage());
            return "";
        }

        if (subagent.getState() == AgentState.ERROR || reply.toString().isBlank()) {
            notifyActivity(activity, id, taskTitle, SubagentActivity.Kind.FAILED, "上下文压缩未生成有效内容");
            return "";
        }
        String result = reply.toString().strip();
        notifyActivity(activity, id, taskTitle, SubagentActivity.Kind.FINISHED, result);
        return result;
    }

    /**
     * 将待压缩的消息序列格式化为供压缩子代理阅读的对话剧本。
     */
    static String buildTranscript(List<Message> slice) {
        StringBuilder builder = new StringBuilder();
        for (Message message : slice) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            if (message instanceof UserMessage user) {
                builder.append("[用户]: ").append(safe(user.getText()));
            } else if (message instanceof AssistantMessage assistant) {
                builder.append("[助手]: ").append(safe(assistant.getText()));
                if (assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty()) {
                    for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                        builder.append("\n  -> 调用工具 ").append(call.name())
                                .append(": ").append(truncate(safe(call.arguments()), 400));
                    }
                }
            } else if (message instanceof ToolResponseMessage toolResponse) {
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    builder.append("[工具结果 ").append(response.name()).append("]: ")
                            .append(truncate(safe(response.responseData()), MAX_TOOL_OUTPUT_CHARS));
                }
            } else if (message instanceof SystemMessage system) {
                builder.append("[系统]: ").append(safe(system.getText()));
            }
        }
        return builder.toString();
    }

    private static void notifyActivity(Consumer<SubagentActivity> activity,
                                       String id,
                                       String task,
                                       SubagentActivity.Kind kind,
                                       String detail) {
        if (activity == null) {
            return;
        }
        try {
            activity.accept(new SubagentActivity(id, SUBAGENT_NAME, task, kind, detail));
        } catch (RuntimeException ignored) {
            // 展示异常不中断压缩
        }
    }

    private static String safe(String text) {
        return text == null ? "" : text.strip();
    }

    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...(已截断)";
    }

    /**
     * 上下文压缩执行结果。
     *
     * @param messages       压缩后的新消息列表
     * @param compactedCount 本次被压缩进摘要的历史消息条数（为 0 表示未发生压缩）
     * @param beforeTotal    压缩前的总消息条数
     * @param afterTotal     压缩后的总消息条数
     * @param beforeTokens   压缩前的估算 Token 数
     * @param afterTokens    压缩后的估算 Token 数
     * @param summary        压缩子代理产出的摘要文本
     * @param ratio          本次使用的压缩比例（0~1）
     */
    public record Result(
            List<Message> messages,
            int compactedCount,
            int beforeTotal,
            int afterTotal,
            int beforeTokens,
            int afterTokens,
            String summary,
            double ratio
    ) {
        public boolean compacted() {
            return compactedCount > 0;
        }
    }
}
