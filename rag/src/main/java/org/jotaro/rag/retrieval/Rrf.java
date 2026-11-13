package org.jotaro.rag.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reciprocal Rank Fusion。同一条结果在多路里都靠前时，分数相加。 */
public final class Rrf {

    private Rrf() {
    }

    public static List<RetrievalHit> fuse(List<List<RetrievalHit>> rankings, int k, int limit) {
        int constant = Math.max(1, k);
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, RetrievalHit> chosen = new LinkedHashMap<>();
        for (List<RetrievalHit> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                RetrievalHit hit = ranking.get(rank);
                String key = hit.route() + ":" + hit.id();
                scores.merge(key, 1.0 / (constant + rank + 1), Double::sum);
                chosen.putIfAbsent(key, hit);
            }
        }
        List<RetrievalHit> fused = new ArrayList<>();
        scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(Math.max(1, limit))
                .forEach(entry -> fused.add(chosen.get(entry.getKey()).withScore(entry.getValue())));
        return fused;
    }
}
