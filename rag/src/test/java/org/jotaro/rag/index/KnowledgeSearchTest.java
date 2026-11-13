package org.jotaro.rag.index;

import jakarta.annotation.Resource;
import org.jotaro.rag.retrieval.HybridSearch;
import org.jotaro.rag.retrieval.RetrievalHit;
import org.jotaro.rag.retrieval.RetrievalQuery;
import org.jotaro.rag.retrieval.RetrievalStage;
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
            HybridSearch.Explanation explanation = search.explain(new RetrievalQuery("AuthFilter", base, 5));
            assertFalse(explanation.hits().isEmpty());
            assertTrue(stage(explanation, "postgres · 词法").hits().stream().anyMatch(hit -> hit.content().contains("AuthFilter")));
            assertEquals("skipped", stage(explanation, "postgres · 向量").status());
            assertEquals("skipped", stage(explanation, "elasticsearch").status());
            assertFalse(stage(explanation, "混合排序").hits().isEmpty());
        } finally {
            jdbc.update("DELETE FROM knowledge_base WHERE name = ?", base);
        }
    }

    @Test
    void customChunkSizeIsStoredAndCanBeRebuilt() {
        String base = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            knowledge.createBase(base, "");
            KnowledgeService.ChunkPreview preview = knowledge.preview("a".repeat(500), 200, 0);
            assertEquals(3, preview.chunks().size());
            assertEquals(200, preview.chunkSize());
            KnowledgeService.DocumentView saved = knowledge.ingest(base, "长文", "a".repeat(500), "a.txt", 200, 0);
            assertEquals(3, saved.chunks());
            assertEquals(200, saved.chunkSize());
            assertEquals(0, saved.chunkOverlap());
            assertEquals("length", saved.chunkMode());
            KnowledgeService.DocumentDetail detail = knowledge.detail(saved.id());
            assertEquals(500, detail.content().length());
            assertEquals(200, detail.chunks().get(0).chars());
            KnowledgeService.DocumentDetail again = knowledge.rechunk(saved.id(), 250, 0);
            assertEquals(2, again.chunks().size());
            assertEquals(250, again.chunkSize());
            assertThrows(IllegalArgumentException.class, () -> knowledge.preview("甲", 100, 0));
        } finally {
            jdbc.update("DELETE FROM knowledge_base WHERE name = ?", base);
        }
    }

    @Test
    void delimiterSplitsTheDocument() {
        String base = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            knowledge.createBase(base, "");
            KnowledgeService.ChunkPreview preview = knowledge.preview("甲\n\n乙", null, null, "delimiter", "\\n\\n");
            assertEquals(List.of("甲", "乙"), preview.chunks().stream().map(KnowledgeService.ChunkPiece::content).toList());
            assertEquals("delimiter", preview.mode());
            KnowledgeService.DocumentView saved = knowledge.ingest(base, "分隔", "甲---乙---丙", "", null, null, "delimiter", "---");
            assertEquals(3, saved.chunks());
            assertEquals("---", saved.chunkDelimiter());
            KnowledgeService.DocumentDetail again = knowledge.rechunk(saved.id(), null, null, "length", null);
            assertEquals("length", again.chunkMode());
            assertEquals("", again.chunkDelimiter());
            assertThrows(IllegalArgumentException.class, () -> knowledge.preview("甲", null, null, "delimiter", ""));
        } finally {
            jdbc.update("DELETE FROM knowledge_base WHERE name = ?", base);
        }
    }

    @Test
    void rejectsABaseNameOutsideThePattern() {
        assertThrows(IllegalArgumentException.class, () -> knowledge.createBase("Bad Name", ""));
    }

    private static RetrievalStage stage(HybridSearch.Explanation explanation, String name) {
        return explanation.stages().stream()
                .filter(item -> name.equals(item.name()))
                .findFirst()
                .orElseThrow();
    }
}
