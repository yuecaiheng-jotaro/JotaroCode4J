package org.jotaro.rag.retrieval;

/**
 * 一条检索结果。{@code route} 是命中的通路名，混合排序后分数是倒数排名之和。
 */
public record RetrievalHit(
        String id,
        String title,
        String content,
        String source,
        String route,
        double score
) {
    public RetrievalHit withScore(double score) {
        return new RetrievalHit(id, title, content, source, route, score);
    }
}
