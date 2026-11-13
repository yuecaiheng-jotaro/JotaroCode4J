package org.jotaro.rag.retrieval;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jotaro.rag.config.RagProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
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

    /** 展开每一阶段的召回，最后一条是混合排序后的 Top N。 */
    public Explanation explain(RetrievalQuery query) {
        List<RetrievalStage> stages = new ArrayList<>();
        List<List<RetrievalHit>> rankings = new ArrayList<>();
        List<Retriever> ordered = new ArrayList<>(retrievers);
        ordered.sort(Comparator.comparingInt(HybridSearch::routeOrder).thenComparing(Retriever::name));
        for (Retriever retriever : ordered) {
            try {
                List<RetrievalStage> part = retriever.stages(query);
                stages.addAll(part);
                for (RetrievalStage stage : part) {
                    if (stage.feed() && "ok".equals(stage.status())) {
                        rankings.add(stage.hits());
                    }
                }
            } catch (RuntimeException exception) {
                log.warn("检索通路 {} 失败: {}", retriever.name(), exception.getMessage());
                stages.add(new RetrievalStage(retriever.name(), "failed", exception.getMessage(), false, List.of()));
            }
        }
        List<RetrievalHit> hits = rankings.isEmpty()
                ? List.of()
                : Rrf.fuse(rankings, properties.getRrfK(), query.limit());
        stages.add(new RetrievalStage(
                "混合排序",
                "ok",
                "各通路倒数排名相加后的前 " + query.limit() + " 条",
                false,
                hits
        ));
        return new Explanation(stages, hits);
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

    public record Explanation(List<RetrievalStage> stages, List<RetrievalHit> hits) {
    }

    private static int routeOrder(Retriever retriever) {
        return switch (retriever.name()) {
            case "postgres" -> 0;
            case "elasticsearch" -> 1;
            case "graph" -> 2;
            default -> 9;
        };
    }
}
