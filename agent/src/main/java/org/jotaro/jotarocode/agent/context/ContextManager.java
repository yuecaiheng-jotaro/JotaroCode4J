package org.jotaro.jotarocode.agent.context;

import org.jotaro.jotarocode.agent.runtime.AssistantStreamer;
import org.jotaro.jotarocode.agent.runtime.ContextCompactor;
import org.jotaro.jotarocode.agent.runtime.RunControl;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个会话的上下文管理器（参考 DeepSeek Harness 的 Event Log + 动态派生 + 压缩检查点架构）。
 *
 * <p>核心职责：
 * <ul>
 *   <li>将每一条新消息作为不可变 {@link ContextEvent} 持久化到 {@link ContextStore}（默认 Redis）；</li>
 *   <li>通过 {@link ContextPipeline#derive} 动态生成发给大模型的精简上下文（包含旧工具输出裁剪与目标锚定）；</li>
 *   <li>当派生后的上下文估算 Token 数超过所选大模型最大上下文的 0.8（可配置）时，派生 {@code compact} 子代理按 70%（可配置）比例压缩历史，
 *       并向 Redis 事件日志追加一条 {@code COMPACTION} 检查点事件（原始事件日志保持完整可追溯）；</li>
 *   <li>提供 {@link #inspect(String)} 供 TUI {@code /context} 和 Web API 实时透视上下文水位、裁剪与压缩统计。</li>
 * </ul>
 */
public final class ContextManager {

    private final String sessionId;
    private final ContextStore store;
    private final ContextCompactor compactor;
    private final ContextPipeline.Options pipelineOptions;
    private final AtomicLong sequence = new AtomicLong();

    public ContextManager(String sessionId,
                          ContextStore store,
                          ContextCompactor compactor,
                          ContextPipeline.Options pipelineOptions) {
        this.sessionId = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
        this.store = store == null ? new InMemoryContextStore() : store;
        this.compactor = compactor;
        this.pipelineOptions = pipelineOptions == null ? ContextPipeline.Options.defaults() : pipelineOptions;
    }

    public String sessionId() {
        return sessionId;
    }

    public ContextCompactor compactor() {
        return compactor;
    }

    /**
     * 追加一条消息事件到持久化事件日志（Redis / 内存）。
     */
    public synchronized void record(Message message) {
        if (message == null) {
            return;
        }
        long seq = sequence.incrementAndGet();
        store.append(sessionId, ContextEvent.fromMessage(seq, message));
    }

    /**
     * 记录或更新当前会话的任务目标（Goal 锚定）。
     */
    public synchronized void setGoal(String goal) {
        if (goal == null || goal.isBlank()) {
            return;
        }
        long seq = sequence.incrementAndGet();
        store.append(sessionId, ContextEvent.builder()
                .seq(seq)
                .type(ContextEvent.Type.GOAL)
                .timestamp(System.currentTimeMillis())
                .text(goal.strip())
                .build());
    }

    /**
     * 清空会话事件日志并重新写入初始系统提示词。
     */
    public synchronized void reset(String systemPrompt) {
        store.clear(sessionId);
        sequence.set(0);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            record(new SystemMessage(systemPrompt));
        }
    }

    /**
     * 获取当前派生后的活跃消息列表（不触发模型压缩，用于只读查看）。
     */
    public synchronized List<Message> activeMessages() {
        return ContextPipeline.derive(store.events(sessionId), pipelineOptions).messages();
    }

    /**
     * 动态派生发往大模型的消息列表：
     * 1. 先经 {@link ContextPipeline} 执行检查点回放与旧工具输出裁剪；
     * 2. 若仍达到当前模型最大上下文窗口的 0.8（{@code triggerThreshold}），自动触发子代理压缩并写入检查点事件。
     */
    public List<Message> deriveForModel(String model, AssistantStreamer streamer, RunControl control) {
        ContextPipeline.Derived derived;
        synchronized (this) {
            derived = ContextPipeline.derive(store.events(sessionId), pipelineOptions);
        }
        if (compactor != null && compactor.enabled() && compactor.shouldCompact(derived.messages(), model)) {
            ContextCompactor.Result result = compact(null, streamer, control);
            if (result.compacted()) {
                synchronized (this) {
                    return ContextPipeline.derive(store.events(sessionId), pipelineOptions).messages();
                }
            }
        }
        return derived.messages();
    }

    /**
     * 执行一次子代理上下文压缩，并将 {@code COMPACTION} 检查点事件追加写入事件日志（不删除原始事件）。
     */
    public ContextCompactor.Result compact(Double customRatio, AssistantStreamer streamer, RunControl control) {
        ContextCompactor activeCompactor = this.compactor != null ? this.compactor : new ContextCompactor();
        double targetRatio = customRatio == null
                ? activeCompactor.ratio()
                : ContextCompactor.normalizeRatio(customRatio);

        ContextPipeline.Derived derived;
        synchronized (this) {
            derived = ContextPipeline.derive(store.events(sessionId), pipelineOptions);
        }
        List<Message> currentMessages = derived.messages();
        List<Long> seqs = derived.messageSeqs();
        ContextCompactor.Result result = activeCompactor.compact(currentMessages, streamer, control, targetRatio);
        if (!result.compacted()) {
            return result;
        }

        // 计算本次被压缩的最后一条消息对应的事件序号 compactedUpToSeq
        int convStart = (!currentMessages.isEmpty() && currentMessages.get(0) instanceof SystemMessage) ? 1 : 0;
        int lastCompactedIdx = Math.min(seqs.size() - 1, convStart + result.compactedCount() - 1);
        long compactedUpToSeq = lastCompactedIdx >= 0 && lastCompactedIdx < seqs.size()
                ? seqs.get(lastCompactedIdx)
                : 0L;

        // 提取压缩摘要文本（优先取结果中的 summary，保持与 ContextCompactor 输出一致）
        int percent = (int) Math.round(result.ratio() * 100);
        String summaryBody = "[历史上下文压缩摘要（已由子代理压缩前 " + result.compactedCount()
                + " 条消息，压缩比例 " + percent + "%）]\n" + result.summary();

        synchronized (this) {
            long seq = sequence.incrementAndGet();
            store.append(sessionId, ContextEvent.builder()
                    .seq(seq)
                    .type(ContextEvent.Type.COMPACTION)
                    .timestamp(System.currentTimeMillis())
                    .text(summaryBody)
                    .compactedUpToSeq(compactedUpToSeq)
                    .compactedCount(result.compactedCount())
                    .compactRatio(result.ratio())
                    .beforeTokens(result.beforeTokens())
                    .afterTokens(result.afterTokens())
                    .build());
        }
        return result;
    }

    /**
     * 生成当前会话的上下文洞察快照（供 TUI {@code /context} 与 Web API 展示）。
     */
    public synchronized ContextSnapshot inspect(String model) {
        ContextPipeline.Derived derived = ContextPipeline.derive(store.events(sessionId), pipelineOptions);
        int estimatedTokens = ContextCompactor.estimateTokens(derived.messages());
        ContextCompactor activeCompactor = this.compactor != null ? this.compactor : new ContextCompactor();
        int modelLimit = activeCompactor.contextLimitFor(model);
        double usagePercent = modelLimit > 0 ? Math.min(100.0, (estimatedTokens * 100.0) / modelLimit) : 0.0;
        return new ContextSnapshot(
                sessionId,
                store.storageKind(),
                model == null ? "" : model,
                derived.rawEventCount(),
                derived.messages().size(),
                derived.compactionCount(),
                derived.prunedToolOutputs(),
                estimatedTokens,
                modelLimit,
                usagePercent,
                activeCompactor.triggerThreshold(),
                activeCompactor.ratio(),
                derived.goal()
        );
    }

    /**
     * 上下文洞察快照视图（类似 DeepSeek Harness 的 {@code dsh-context}）。
     */
    public record ContextSnapshot(
            String sessionId,
            String storage,
            String model,
            int rawEventCount,
            int activeMessageCount,
            int compactionCount,
            int prunedToolOutputs,
            int estimatedTokens,
            int modelContextLimit,
            double usagePercent,
            double triggerThreshold,
            double compactRatio,
            String goal
    ) {
    }
}
