package org.jotaro.rag.retrieval;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jotaro.rag.config.RagProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 把所有打开的通路各查一遍，再做混合排序。某一路失败只记日志，不拖垮其余通路。 */
@Slf4j
@Service
public class HybridSearch {

    @Resource
    private List<Retriever> retrievers;
    @Resource
    private RagProperties properties;

    public List<RetrievalHit> search(RetrievalQuery query) {
        List<List<RetrievalHit>> rankings = new ArrayList<>();
        for (Retriever retriever : retrievers) {
            if (!retriever.enabled()) {
                continue;
            }
            try {
                rankings.add(retriever.retrieve(query));
            } catch (RuntimeException exception) {
                log.warn("检索通路 {} 失败: {}", retriever.name(), exception.getMessage());
            }
        }
        return Rrf.fuse(rankings, properties.getRrfK(), query.limit());
    }

    public List<RouteStatus> routes() {
        List<RouteStatus> statuses = new ArrayList<>();
        for (Retriever retriever : retrievers) {
            statuses.add(new RouteStatus(retriever.name(), retriever.enabled()));
        }
        return statuses;
    }

    public record RouteStatus(String name, boolean enabled) {
    }
}
