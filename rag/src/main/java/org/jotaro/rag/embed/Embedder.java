package org.jotaro.rag.embed;

/**
 * 文本向量化（Embedding）接口，负责将查询词和文档分段（Chunk）转换为浮点数向量。
 * <p>
 * 默认关闭，此时 Postgres 通路只做全文检索和三元组（Trigram）词法检索。
 * 注册真实模型实现并打开 {@code rag.embedding.enabled=true} 后，
 * 入库时会自动计算并写入 {@code chunk.embedding} 列，检索时同一张表的向量列会参与 RRF 混合排序。
 */
public interface Embedder {

    /**
     * 判断当前向量化组件是否已启用。
     *
     * @return {@code true} 表示已启用向量化能力，入库与检索阶段均会调用 {@link #embed(String)}
     */
    boolean enabled();

    /**
     * 将输入文本编码为固定维度的浮点数特征向量。
     *
     * @param text 待向量化的文本内容（文档片段或用户查询语句）
     * @return 对应的浮点数向量数组
     */
    float[] embed(String text);
}
