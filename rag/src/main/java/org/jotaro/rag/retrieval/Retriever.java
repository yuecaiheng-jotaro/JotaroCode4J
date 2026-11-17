package org.jotaro.rag.retrieval;

import java.util.List;

/**
 * 检索通路抽象接口。
 * <p>
 * Postgres、Elasticsearch、GraphQL 各自实现该接口并注册为 Spring 组件。
 * {@link HybridSearch} 在检索时只会调用 {@link #enabled()} 为 {@code true} 的实现，
 * 并将各通路返回的排序列表通过 {@link Rrf}（倒数排名融合）合并为最终结果。
 */
public interface Retriever {

    /**
     * 返回当前检索通路的唯一标识名称（如 {@code postgres}、{@code elasticsearch}、{@code graph}）。
     *
     * @return 通路名称
     */
    String name();

    /**
     * 判断当前检索通路是否已启用。
     *
     * @return {@code true} 表示参与混合检索
     */
    boolean enabled();

    /**
     * 根据查询条件执行本通路的检索，返回按本通路相关性从高到低排序的命中列表。
     *
     * @param query 检索请求参数
     * @return 命中片段列表
     */
    List<RetrievalHit> retrieve(RetrievalQuery query);

    /**
     * 返回本通路内部的各个检索子阶段，用于检索诊断页面展示各阶段详情。
     * <p>
     * 默认实现仅包含本通路自身的一个阶段；像 Postgres 这类内部包含多子通道（词法 + 向量 + 内部融合）的实现可以重写此方法。
     * 只有 {@code feed} 为 {@code true} 的阶段会被外层 {@link HybridSearch} 采集并送入最终混合排序。
     *
     * @param query 检索请求参数
     * @return 阶段执行明细列表
     */
    default List<RetrievalStage> stages(RetrievalQuery query) {
        if (!enabled()) {
            return List.of(new RetrievalStage(name(), "skipped", "未启用", false, List.of()));
        }
        return List.of(new RetrievalStage(name(), "ok", "", true, retrieve(query)));
    }
}
