package org.jotaro.rag.retrieval.postgres;

import org.jotaro.rag.config.RagProperties;
import org.jotaro.rag.embed.Embedder;
import org.jotaro.rag.retrieval.RetrievalHit;
import org.jotaro.rag.retrieval.RetrievalQuery;
import org.jotaro.rag.retrieval.RetrievalStage;
import org.jotaro.rag.retrieval.Retriever;
import org.jotaro.rag.retrieval.Rrf;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Postgres 这一路内部还有两条通道：全文加三元组，以及 pgvector。
 * 向量模型没打开时，第二路为空，结果就是词法检索。
 */
@Slf4j
@Component
public class PostgresRetriever implements Retriever {

    private static final String LEXICAL = """
            SELECT c.id::text AS id, d.title AS title, c.content AS content, d.source AS source,
                   ts_rank_cd(c.search_vector, plainto_tsquery('simple', :query))
                       + word_similarity(:query, c.content)
                       + CASE WHEN strpos(lower(c.content), lower(:query)) > 0 THEN 1 ELSE 0 END AS score
            FROM chunk c
            JOIN document d ON d.id = c.document_id
            JOIN knowledge_base b ON b.id = c.base_id
            WHERE (:base = '' OR b.name = :base)
              AND (
                    c.search_vector @@ plainto_tsquery('simple', :query)
                    OR word_similarity(:query, c.content) > 0.12
                    OR strpos(lower(c.content), lower(:query)) > 0
                  )
            ORDER BY score DESC
            LIMIT :limit
            """;

    private static final String VECTOR = """
            SELECT c.id::text AS id, d.title AS title, c.content AS content, d.source AS source,
                   1 - (c.embedding <=> CAST(:embedding AS vector)) AS score
            FROM chunk c
            JOIN document d ON d.id = c.document_id
            JOIN knowledge_base b ON b.id = c.base_id
            WHERE c.embedding IS NOT NULL
              AND (:base = '' OR b.name = :base)
            ORDER BY c.embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """;

    @Resource
    private NamedParameterJdbcTemplate jdbc;
    @Resource
    private Embedder embedder;
    @Resource
    private RagProperties properties;

    @Override
    public String name() {
        return "postgres";
    }

    @Override
    public boolean enabled() {
        return properties.getRoutes().contains(name());
    }

    @Override
    public List<RetrievalHit> retrieve(RetrievalQuery query) {
        for (RetrievalStage stage : stages(query)) {
            if (stage.feed()) {
                return stage.hits();
            }
        }
        return List.of();
    }

    // 词法和向量分开返回，方便管理页对照每一阶段的原文。融合结果才进入外层混合排序。
    @Override
    public List<RetrievalStage> stages(RetrievalQuery query) {
        if (!enabled()) {
            return List.of(new RetrievalStage(name(), "skipped", "未加入混合检索", false, List.of()));
        }
        if (query.text() == null || query.text().isBlank()) {
            return List.of();
        }
        List<RetrievalHit> lexical = query(LEXICAL, params(query));
        VectorOutcome vector = vectorOutcome(query);
        boolean hasVector = !vector.hits().isEmpty();
        List<RetrievalHit> fused = hasVector
                ? Rrf.fuse(List.of(lexical, vector.hits()), properties.getRrfK(), query.limit())
                : lexical;
        String fusionMessage = hasVector
                ? "词法和向量按倒数排名融合，再交给混合排序"
                : "向量没有召回，这一阶段与词法相同";
        return List.of(
                new RetrievalStage("postgres · 词法", "ok", "全文、三元组相似度和子串", false, lexical),
                new RetrievalStage("postgres · 向量", vector.status(), vector.message(), false, vector.hits()),
                new RetrievalStage("postgres · 融合", "ok", fusionMessage, true, fused)
        );
    }

    private VectorOutcome vectorOutcome(RetrievalQuery query) {
        if (!embedder.enabled()) {
            return new VectorOutcome("skipped", "向量模型未开启", List.of());
        }
        try {
            float[] embedding = embedder.embed(query.text());
            MapSqlParameterSource params = params(query).addValue("embedding", vectorLiteral(embedding));
            return new VectorOutcome("ok", "按余弦距离召回", query(VECTOR, params));
        } catch (RuntimeException exception) {
            log.warn("向量通道跳过: {}", exception.getMessage());
            return new VectorOutcome("failed", exception.getMessage(), List.of());
        }
    }

    private record VectorOutcome(String status, String message, List<RetrievalHit> hits) {
    }

    private List<RetrievalHit> query(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql, params, (rs, row) -> new RetrievalHit(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("source"),
                name(),
                rs.getDouble("score")
        ));
    }

    private static MapSqlParameterSource params(RetrievalQuery query) {
        String base = query.knowledgeBase() == null ? "" : query.knowledgeBase().trim();
        return new MapSqlParameterSource()
                .addValue("query", query.text())
                .addValue("base", base)
                .addValue("limit", query.limit());
    }

    static String vectorLiteral(float[] embedding) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(embedding[index]);
        }
        return builder.append(']').toString();
    }
}
