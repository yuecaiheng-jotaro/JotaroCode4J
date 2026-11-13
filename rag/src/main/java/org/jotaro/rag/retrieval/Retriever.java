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
}
