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
 * PostgreSQL 核心混合检索器（词法检索 + pgvector 向量检索 + 内部 RRF 融合）。
 * <p>
 * Postgres 通路内部包含两条召回子通道：
 * <ol>
 *   <li><b>词法通道（{@link #LEXICAL}）</b>：结合 PostgreSQL 全文检索（{@code tsvector @@ plainto_tsquery} + {@code ts_rank_cd}）、
 *       {@code pg_trgm} 三元组词相似度（{@code word_similarity > 0.12}）以及大小写不敏感子串精确包含（{@code strpos}），兼顾英文分词、中文连续字串与模糊匹配；</li>
 *   <li><b>向量通道（{@link #VECTOR}）</b>：当 {@link Embedder#enabled()} 开启时，将查询文本编码为向量，使用 {@code pgvector} 的余弦距离算子 {@code <=>} 召回语义最相近的分段。</li>
 * </ol>
 * 若向量通道开启且有召回结果，先在 Postgres 通路内部用 {@link Rrf} 将词法与向量结果融合，再作为 {@code feed=true} 的阶段输出给外层 {@link org.jotaro.rag.retrieval.HybridSearch}。
 */
@Slf4j
@Component
public class PostgresRetriever implements Retriever {

    /**
     * 词法检索 SQL（全文检索 + pg_trgm 三元组相似度 + 子串包含三合一打分与过滤）：
     * <ul>
     *   <li>{@code ts_rank_cd(c.search_vector, plainto_tsquery('simple', :query))}：计算全文检索覆盖密度得分；</li>
     *   <li>{@code word_similarity(:query, c.content)}：基于 pg_trgm 计算查询串与正文局部词的 3-gram 相似度（过滤阈值 > 0.12）；</li>
     *   <li>{@code CASE WHEN strpos(lower(c.content), lower(:query)) > 0 THEN 1 ELSE 0 END}：若正文完整包含查询子串（忽略大小写），额外加 1 分奖励并保底召回。</li>
     * </ul>
     */
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

    /**
     * 向量检索 SQL（基于 pgvector 余弦距离 {@code <=>}）：
     * <ul>
     *   <li>{@code c.embedding <=> CAST(:embedding AS vector)} 计算余弦距离（越小越相近），按距离升序取 Top-N；</li>
     *   <li>{@code 1 - (c.embedding <=> ...)} 将余弦距离还原为余弦相似度作为原始 {@code score}。</li>
     * </ul>
     */
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

    /** 支持命名参数的 JDBC 模板 */
    @Resource
    private NamedParameterJdbcTemplate jdbc;
    /** 查询文本向量化器 */
    @Resource
    private Embedder embedder;
    /** RAG 全局配置（用于读取激活路由列表及 RRF 常数 k） */
    @Resource
    private RagProperties properties;

    /**
     * 返回通路标识名 {@code postgres}。
     */
    @Override
    public String name() {
        return "postgres";
    }

    /**
     * 当 {@code rag.routes} 列表中包含 {@code postgres} 时启用本通路。
     */
    @Override
    public boolean enabled() {
        return properties.getRoutes().contains(name());
    }

    /**
     * 执行 Postgres 检索并返回用于外层融合的最终阶段结果（即“postgres · 融合”阶段的命中列表）。
     *
     * @param query 检索查询参数
     * @return Postgres 通路融合后的命中列表
     */
    @Override
    public List<RetrievalHit> retrieve(RetrievalQuery query) {
        for (RetrievalStage stage : stages(query)) {
            if (stage.feed()) {
                return stage.hits();
            }
        }
        return List.of();
    }

    /**
     * 执行并返回 Postgres 内部的三个可观测阶段：“postgres · 词法”、“postgres · 向量”与“postgres · 融合”。
     * 词法和向量分开返回，方便管理页对照每一阶段的原文；只有最后的“postgres · 融合”阶段标记 {@code feed=true} 进入外层混合排序。
     *
     * @param query 检索查询参数
     * @return Postgres 内部三阶段列表
     */
    // 词法和向量分开返回，方便管理页对照每一阶段的原文。融合结果才进入外层混合排序。
    @Override
    public List<RetrievalStage> stages(RetrievalQuery query) {
        if (!enabled()) {
            return List.of(new RetrievalStage(name(), "skipped", "未加入混合检索", false, List.of()));
        }
        if (query.text() == null || query.text().isBlank()) {
            return List.of();
        }
        // 第一阶段：执行词法检索（全文 + 三元组 + 子串）
        List<RetrievalHit> lexical = query(LEXICAL, params(query));
        // 第二阶段：尝试执行 pgvector 向量检索（若未开启或异常则优雅降级）
        VectorOutcome vector = vectorOutcome(query);
        boolean hasVector = !vector.hits().isEmpty();
        // 第三阶段：若向量通道有命中，则与词法结果做一次通路内 RRF 融合；否则直接沿用词法结果
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

    /**
     * 执行向量检索子通道，捕获可能的模型调用或 SQL 异常并转化为阶段状态，避免影响词法检索结果。
     */
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

    /**
     * 封装向量子通道的执行状态、提示消息与命中结果。
     */
    private record VectorOutcome(String status, String message, List<RetrievalHit> hits) {
    }

    /**
     * 执行通用检索 SQL 并将结果集映射为 {@link RetrievalHit} 列表。
     */
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

    /**
     * 构造 SQL 查询命名参数（{@code query}、{@code base}、{@code limit}）。
     */
    private static MapSqlParameterSource params(RetrievalQuery query) {
        String base = query.knowledgeBase() == null ? "" : query.knowledgeBase().trim();
        return new MapSqlParameterSource()
                .addValue("query", query.text())
                .addValue("base", base)
                .addValue("limit", query.limit());
    }

    /**
     * 将查询向量数组格式化为 pgvector 可识别的 {@code [v1,v2,...]} 字符串字面量。
     */
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
