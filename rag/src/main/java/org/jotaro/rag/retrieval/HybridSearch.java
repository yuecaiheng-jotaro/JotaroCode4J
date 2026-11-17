package org.jotaro.rag.retrieval;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jotaro.rag.config.RagProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 多路混合检索编排服务（Hybrid Search）。
 * <p>
 * 负责遍历所有已注册的 {@link Retriever} 通路（如 Postgres、Elasticsearch、GraphQL）：
 * <ul>
 *   <li>依次调用已启用的检索通路收集候选排序结果；</li>
 *   <li>具备单路容错降级能力：某一路抛出异常时仅记录告警日志（或在 {@link #explain} 中标记为 {@code failed}），不会中断其他正常通路；</li>
 *   <li>最后通过 {@link Rrf}（倒数排名融合算法）将多路召回列表合成为统一 Top-N 结果。</li>
 * </ul>
 */
@Slf4j
@Service
public class HybridSearch {

    /** Spring 容器注入的所有检索通路实现 */
    @Resource
    private List<Retriever> retrievers;
    /** RAG 配置属性（提供 RRF 平滑常数 {@code rrfK} 等参数） */
    @Resource
    private RagProperties properties;

    /**
     * 执行多路召回并使用 RRF 融合排序返回最终 Top-N 命中片段。
     *
     * @param query 检索查询请求（包含查询词、目标知识库及返回条数限制）
     * @return 经过倒数排名融合后的检索命中列表
     */
    public List<RetrievalHit> search(RetrievalQuery query) {
        List<List<RetrievalHit>> rankings = new ArrayList<>();
        for (Retriever retriever : retrievers) {
            // 跳过未启用的检索通路
            if (!retriever.enabled()) {
                continue;
            }
            try {
                rankings.add(retriever.retrieve(query));
            } catch (RuntimeException exception) {
                // 单路故障隔离：记录日志并继续汇总其余可用通路的结果
                log.warn("检索通路 {} 失败: {}", retriever.name(), exception.getMessage());
            }
        }
        // 使用 RRF 将各路排序融合为统一的 Top-K 列表
        return Rrf.fuse(rankings, properties.getRrfK(), query.limit());
    }

    /**
     * 展开每一路、每一阶段的详细召回过程，并在最后附上混合排序后的 Top-N 阶段。
     * 供前端管理页面的“检索诊断 / Explain”视图对照查看各通道原始召回与融合得分。
     *
     * @param query 检索查询请求
     * @return 包含各阶段明细与最终融合结果的解释对象
     */
    public Explanation explain(RetrievalQuery query) {
        List<RetrievalStage> stages = new ArrayList<>();
        List<List<RetrievalHit>> rankings = new ArrayList<>();
        List<Retriever> ordered = new ArrayList<>(retrievers);
        // 按固定优先级（postgres -> elasticsearch -> graph）排序，保证管理页展示顺序稳定
        ordered.sort(Comparator.comparingInt(HybridSearch::routeOrder).thenComparing(Retriever::name));
        for (Retriever retriever : ordered) {
            try {
                List<RetrievalStage> part = retriever.stages(query);
                stages.addAll(part);
                // 仅收集标记为 feed=true 且状态为 ok 的阶段参与外层 RRF 混合排序
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
        // 追加最后一个汇总阶段，展示 RRF 融合后的最终 Top-N
        stages.add(new RetrievalStage(
                "混合排序",
                "ok",
                "各通路倒数排名相加后的前 " + query.limit() + " 条",
                false,
                hits
        ));
        return new Explanation(stages, hits);
    }

    /**
     * 获取所有已注册检索通路的名称及其启用状态。
     *
     * @return 检索通路状态列表
     */
    public List<RouteStatus> routes() {
        List<RouteStatus> statuses = new ArrayList<>();
        for (Retriever retriever : retrievers) {
            statuses.add(new RouteStatus(retriever.name(), retriever.enabled()));
        }
        return statuses;
    }

    /**
     * 检索通路启用状态视图。
     *
     * @param name    通路名称（如 {@code postgres}、{@code elasticsearch}、{@code graph}）
     * @param enabled 当前是否已启用
     */
    public record RouteStatus(String name, boolean enabled) {
    }

    /**
     * 混合检索全链路解释结果。
     *
     * @param stages 按执行顺序排列的各子阶段召回明细（最后一项为“混合排序”）
     * @param hits   最终融合后的检索命中列表
     */
    public record Explanation(List<RetrievalStage> stages, List<RetrievalHit> hits) {
    }

    /**
     * 定义各检索通路在诊断视图中的展示先后顺序。
     */
    private static int routeOrder(Retriever retriever) {
        return switch (retriever.name()) {
            case "postgres" -> 0;
            case "elasticsearch" -> 1;
            case "graph" -> 2;
            default -> 9;
        };
    }
}
