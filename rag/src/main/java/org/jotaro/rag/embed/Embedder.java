package org.jotaro.rag.embed;

/**
 * 查询和片段的向量。默认关闭，Postgres 通路只做全文和三元组。
 * 换一个实现并打开 {@code rag.embedding.enabled} 后，同一张表的 {@code embedding} 列会参与混合排序。
 */
public interface Embedder {

    boolean enabled();

    float[] embed(String text);
}
