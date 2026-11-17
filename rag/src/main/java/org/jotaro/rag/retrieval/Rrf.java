package org.jotaro.rag.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 倒数排名融合算法（Reciprocal Rank Fusion, RRF）工具类。
 * <p>
 * 不同检索通道（如全文词法打分、pgvector 余弦相似度、Elasticsearch BM25）的原始分数量纲差异巨大，无法直接比较或线性相加。
 * RRF 忽略原始绝对分数，仅利用候选结果在各自通道内的名次（Rank，从 0 开始计为第 1 名）计算贡献分：
 * <pre>
 *   score(d) = Σ  1 / (k + rank_i(d) + 1)
 * </pre>
 * 其中 {@code k} 为平滑常数（通常取 60），用于抑制单一通道极端高排名的主导效应。
 * 同一条结果在多个通道中排名都靠前时，其倒数排名得分会累加，从而在最终融合榜单中排到前面。
 */
public final class Rrf {

    private Rrf() {
    }

    /**
     * 将多路有序召回列表按 RRF 算法融合重排，并截取前 {@code limit} 条结果。
     *
     * @param rankings 多路检索各自返回的有序命中列表（每个子列表内部按相关性从高到低排列）
     * @param k        RRF 平滑常数（最小按 1 兜底，默认配置为 60）
     * @param limit    融合后保留的最大结果条数（最小按 1 兜底）
     * @return 按 RRF 累加得分降序排列的 Top-N 命中列表（每项的 {@code score} 已更新为 RRF 融合得分）
     */
    public static List<RetrievalHit> fuse(List<List<RetrievalHit>> rankings, int k, int limit) {
        // 确保平滑常数 k 至少为 1，防止除零或负数异常
        int constant = Math.max(1, k);
        // 维护每个唯一候选键（route:id）的累加 RRF 分数，使用 LinkedHashMap 保持首次出现的稳定顺序（在同分时可预测）
        Map<String, Double> scores = new LinkedHashMap<>();
        // 保存每个唯一候选键对应的首次命中实体
        Map<String, RetrievalHit> chosen = new LinkedHashMap<>();
        for (List<RetrievalHit> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                RetrievalHit hit = ranking.get(rank);
                // 同一通路里的同一片段（例如 Postgres 词法与 Postgres 向量召回了同一个 chunk）可以累加贡献；
                // 不同通路的本地 ID 互相独立，因此以 route + ":" + id 作为归并主键。
                String key = hit.route() + ":" + hit.id();
                // 第 rank 项（0-based，对应第 rank+1 名）的倒数排名增量为 1.0 / (constant + rank + 1)
                scores.merge(key, 1.0 / (constant + rank + 1), Double::sum);
                chosen.putIfAbsent(key, hit);
            }
        }
        List<RetrievalHit> fused = new ArrayList<>();
        // 按累加后的 RRF 分数从高到低排序，取前 limit 条并将融合总分写入 RetrievalHit
        scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(Math.max(1, limit))
                .forEach(entry -> fused.add(chosen.get(entry.getKey()).withScore(entry.getValue())));
        return fused;
    }
}
