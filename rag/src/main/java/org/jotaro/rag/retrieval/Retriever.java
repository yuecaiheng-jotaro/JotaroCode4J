package org.jotaro.rag.retrieval;

import java.util.List;

/**
 * 一路检索。Postgres、Elasticsearch、GraphQL 各自实现这个接口并标成组件。
 * {@link HybridSearch} 只调用 {@link #enabled()} 为真的实现，再用倒数排名融合。
 */
public interface Retriever {

    String name();

    boolean enabled();

    List<RetrievalHit> retrieve(RetrievalQuery query);

    /**
     * 这一路内部的各个阶段。默认只给出本路自己的排序。
     * 只有 {@code feed} 为真的阶段会进入最终混合排序。
     */
    default List<RetrievalStage> stages(RetrievalQuery query) {
        if (!enabled()) {
            return List.of(new RetrievalStage(name(), "skipped", "未启用", false, List.of()));
        }
        return List.of(new RetrievalStage(name(), "ok", "", true, retrieve(query)));
    }
}
