package org.jotaro.jotarocode.agent.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文动态派生流水线（参考 DeepSeek Harness 的 {@code deriveMessages()} 设计）。
 *
 * <p>流水线阶段：
 * <ol>
 *   <li><b>检查点回放（Checkpoint Replay）</b>：从不可变事件日志中定位最新的 {@code COMPACTION} 检查点，
 *       仅回放 {@code seq > compactedUpToSeq} 的活跃对话事件，旧事件由摘要替代。</li>
 *   <li><b>目标锚定（Goal Anchoring）</b>：若会话记录了 {@code GOAL} 事件，将其锚定在派生出的 {@link SystemMessage} 末尾，
 *       确保多轮工具调用或多次压缩后不偏离主线目标。</li>
 *   <li><b>旧工具输出裁剪（Tool Result Pruner）</b>：在触发大模型子代理压缩之前，先对较早轮次（非最近 {@code keepRecentToolTurns} 轮）
 *       的超大工具输出做无损视图裁剪（只裁剪发给模型的派生视图，Redis 里的原始事件日志保持完整），大幅延缓上下文膨胀。</li>
 * </ol>
 */
public final class ContextPipeline {

    private ContextPipeline() {
    }

    /**
     * 派生流水线配置项。
     *
     * @param pruneOldToolOutputs 是否启用旧工具输出裁剪（类似 {@code compaction-tool-result-pruner}）
     * @param keepRecentToolTurns 完整保留最近多少个工具返回轮次不裁剪（默认 4）
     * @param toolPruneChars      超过多少字符的旧工具输出才会被裁剪（默认 800）
     */
    public record Options(
            boolean pruneOldToolOutputs,
            int keepRecentToolTurns,
            int toolPruneChars
    ) {
        public static Options defaults() {
            return new Options(true, 4, 800);
        }
    }

    /**
     * 派生结果（含可直接发给大模型的消息列表、事件序号映射与观测统计指标）。
     */
    public record Derived(
            List<Message> messages,
            List<Long> messageSeqs,
            int rawEventCount,
            int compactionCount,
            int prunedToolOutputs,
            String goal
    ) {
    }

    /**
     * 从完整的不可变事件日志动态派生出当前活跃的对话上下文。
     */
    public static Derived derive(List<ContextEvent> events, Options options) {
        Options opts = options == null ? Options.defaults() : options;
        if (events == null || events.isEmpty()) {
            return new Derived(List.of(), List.of(), 0, 0, 0, "");
        }

        ContextEvent latestSystem = null;
        ContextEvent latestGoal = null;
        ContextEvent latestCompaction = null;
        int compactionCount = 0;

        for (ContextEvent event : events) {
            if (event == null || event.getType() == null) {
                continue;
            }
            switch (event.getType()) {
                case SYSTEM -> latestSystem = event;
                case GOAL -> latestGoal = event;
                case COMPACTION -> {
                    latestCompaction = event;
                    compactionCount++;
                }
                default -> {
                }
            }
        }

        long compactedUpTo = latestCompaction != null && latestCompaction.getCompactedUpToSeq() != null
                ? latestCompaction.getCompactedUpToSeq()
                : 0L;

        // 收集检查点之后的活跃对话事件
        List<ContextEvent> activeEvents = new ArrayList<>();
        for (ContextEvent event : events) {
            if (event == null || event.getType() == null) {
                continue;
            }
            if (event.getType() == ContextEvent.Type.USER
                    || event.getType() == ContextEvent.Type.ASSISTANT
                    || event.getType() == ContextEvent.Type.TOOL_RESPONSE) {
                if (event.getSeq() > compactedUpTo) {
                    activeEvents.add(event);
                }
            }
        }

        // 防护：若检查点恰好把 ToolResponseMessage 留在首位，跳过孤立的 ToolResponse
        int firstValid = 0;
        while (firstValid < activeEvents.size()
                && activeEvents.get(firstValid).getType() == ContextEvent.Type.TOOL_RESPONSE) {
            firstValid++;
        }
        if (firstValid > 0) {
            activeEvents = activeEvents.subList(firstValid, activeEvents.size());
        }

        // 统计活跃事件中的工具返回总数，以便对较早轮次执行 Tool Result Pruning
        int totalToolResponses = 0;
        for (ContextEvent event : activeEvents) {
            if (event.getType() == ContextEvent.Type.TOOL_RESPONSE) {
                totalToolResponses++;
            }
        }

        List<Message> messages = new ArrayList<>();
        List<Long> messageSeqs = new ArrayList<>();
        String goalText = latestGoal != null && latestGoal.getText() != null ? latestGoal.getText().strip() : "";

        // 1. 构造 SystemMessage（并锚定 Goal）
        if (latestSystem != null || !goalText.isEmpty()) {
            String baseSystem = latestSystem != null && latestSystem.getText() != null ? latestSystem.getText() : "";
            String combinedSystem = goalText.isEmpty()
                    ? baseSystem
                    : (baseSystem.isEmpty() ? "[当前任务目标]\n" + goalText : baseSystem + "\n\n[当前任务目标]\n" + goalText);
            messages.add(new SystemMessage(combinedSystem));
            messageSeqs.add(latestSystem != null ? latestSystem.getSeq() : 0L);
        }

        // 2. 注入最新压缩检查点摘要
        if (latestCompaction != null && latestCompaction.getText() != null && !latestCompaction.getText().isBlank()) {
            messages.add(new UserMessage(latestCompaction.getText()));
            messageSeqs.add(latestCompaction.getSeq());
            if (!activeEvents.isEmpty() && activeEvents.get(0).getType() == ContextEvent.Type.USER) {
                messages.add(new AssistantMessage("已记录上述历史上下文摘要。"));
                messageSeqs.add(latestCompaction.getSeq());
            }
        }

        // 3. 回放活跃事件并按策略裁剪较早轮次的超大工具输出
        int toolSeen = 0;
        int prunedCount = 0;
        int keepRecent = Math.max(0, opts.keepRecentToolTurns());
        int pruneThreshold = Math.max(100, opts.toolPruneChars());

        for (ContextEvent event : activeEvents) {
            if (event.getType() == ContextEvent.Type.TOOL_RESPONSE) {
                toolSeen++;
                boolean isOldToolTurn = opts.pruneOldToolOutputs() && (totalToolResponses - toolSeen) >= keepRecent;
                if (isOldToolTurn && event.getToolResults() != null) {
                    List<ToolResponseMessage.ToolResponse> prunedResponses = new ArrayList<>();
                    for (ContextEvent.ToolResultData data : event.getToolResults()) {
                        String raw = data.getResponseData() == null ? "" : data.getResponseData();
                        if (raw.length() > pruneThreshold) {
                            prunedCount++;
                            String stub = "[已裁剪旧工具输出: " + data.getName()
                                    + "，原长 " + raw.length() + " 字符（原始日志保留在事件存储中）]";
                            prunedResponses.add(new ToolResponseMessage.ToolResponse(data.getId(), data.getName(), stub));
                        } else {
                            prunedResponses.add(new ToolResponseMessage.ToolResponse(data.getId(), data.getName(), raw));
                        }
                    }
                    messages.add(ToolResponseMessage.builder().responses(prunedResponses).build());
                    messageSeqs.add(event.getSeq());
                    continue;
                }
            }
            messages.add(event.toMessage());
            messageSeqs.add(event.getSeq());
        }

        return new Derived(
                List.copyOf(messages),
                List.copyOf(messageSeqs),
                events.size(),
                compactionCount,
                prunedCount,
                goalText
        );
    }
}
