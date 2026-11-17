package org.jotaro.rag.retrieval;

/**
 * 封装一次知识库检索请求的参数。
 *
 * @param text          用户输入的查询文本（关键词或自然语言问题）
 * @param knowledgeBase 限定检索的知识库名称；空字符串表示跨所有知识库检索
 * @param limit         期望返回的最大命中条数（Top-N）
 */
public record RetrievalQuery(String text, String knowledgeBase, int limit) {
}
