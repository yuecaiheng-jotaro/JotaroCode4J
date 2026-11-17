package org.jotaro.rag.retrieval;

import java.util.List;

/**
 * 表示检索流水线中的一个可观测阶段（用于检索诊断与过程解释）。
 * <p>
 * 例如 Postgres 通路会拆分为“postgres · 词法”、“postgres · 向量”和“postgres · 融合”三个阶段，
 * 其中只有 {@code feed = true} 的阶段（如“postgres · 融合”）会作为该通路的最终输出送入外层 {@link HybridSearch} 进行跨通路 RRF 混合排序。
 *
 * @param name    阶段显示名称（如 {@code postgres · 词法}、{@code elasticsearch}、{@code 混合排序}）
 * @param status  阶段执行状态，取值为 {@code ok}（正常）、{@code skipped}（未启用或跳过）、{@code failed}（异常失败）
 * @param message 状态说明或融合策略提示文案
 * @param feed    是否为向最终外层混合排序供稿的阶段（{@code true} 表示其 {@code hits} 会进入外层 RRF）
 * @param hits    该阶段产出的命中结果列表
 */
public record RetrievalStage(
        String name,
        String status,
        String message,
        boolean feed,
        List<RetrievalHit> hits
) {
}
