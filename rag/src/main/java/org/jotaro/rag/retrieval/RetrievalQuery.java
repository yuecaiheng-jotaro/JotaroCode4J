package org.jotaro.rag.retrieval;

/**
 * @param knowledgeBase 空字符串表示所有知识库
 */
public record RetrievalQuery(String text, String knowledgeBase, int limit) {
}
