package org.jotaro.rag.index;

import jakarta.annotation.Resource;
import org.jotaro.rag.retrieval.HybridSearch;
import org.jotaro.rag.retrieval.RetrievalHit;
import org.jotaro.rag.retrieval.RetrievalQuery;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeSearchTest {

    @Resource
    private KnowledgeService knowledge;
    @Resource
    private HybridSearch search;
    @Resource
    private JdbcTemplate jdbc;

    @Test
    void lexicalSearchFindsAnIngestedChunk() {
        String base = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            knowledge.createBase(base, "测试");
            knowledge.ingest(base, "登录", "登录从 AuthFilter 进入，校验 token 后放行。", "auth.md");
            List<RetrievalHit> hits = search.search(new RetrievalQuery("AuthFilter", base, 5));
            assertFalse(hits.isEmpty());
            assertTrue(hits.get(0).content().contains("AuthFilter"));
            assertEquals("postgres", hits.get(0).route());
            assertEquals("登录", hits.get(0).title());
        } finally {
            jdbc.update("DELETE FROM knowledge_base WHERE name = ?", base);
        }
    }

    @Test
    void rejectsABaseNameOutsideThePattern() {
        assertThrows(IllegalArgumentException.class, () -> knowledge.createBase("Bad Name", ""));
    }
}
