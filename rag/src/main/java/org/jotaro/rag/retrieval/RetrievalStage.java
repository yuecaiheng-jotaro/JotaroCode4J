package org.jotaro.rag.retrieval;

import java.util.List;

/**
 * 检索中的一个阶段。{@code feed} 为真时，这个排序会进入最终的混合排序。
 * {@code status} 取 ok、skipped、failed。
 */
public record RetrievalStage(
        String name,
        String status,
        String message,
        boolean feed,
        List<RetrievalHit> hits
) {
}
