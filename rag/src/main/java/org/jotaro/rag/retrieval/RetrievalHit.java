package org.jotaro.rag.retrieval;

/**
 * 单条检索命中结果（不可变值对象）。
 * <p>
 * 在单路召回阶段，{@code score} 表示该通路的原始相关性得分（如词法得分、余弦相似度或 ES {@code _score}）；
 * 在经过 {@link Rrf} 混合排序后，{@code score} 会被替换为倒数排名累加得分。
 *
 * @param id      分段或外部文档的唯一标识 ID
 * @param title   所属文档标题
 * @param content 命中的分段文本内容
 * @param source  文档来源信息（如文件路径、URL 或业务标识）
 * @param route   产出该命中的检索通路名称（如 {@code postgres}、{@code elasticsearch}、{@code graph}）
 * @param score   相关性得分（单路原始分数或 RRF 融合分数）
 */
public record RetrievalHit(
        String id,
        String title,
        String content,
        String source,
        String route,
        double score
) {
    /**
     * 基于当前命中创建一个仅替换 {@code score} 的新实例（用于 RRF 融合后回填倒数排名总分）。
     *
     * @param score 新的排序得分
     * @return 更新分数后的 {@link RetrievalHit} 副本
     */
    public RetrievalHit withScore(double score) {
        return new RetrievalHit(id, title, content, source, route, score);
    }
}
