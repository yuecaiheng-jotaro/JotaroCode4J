package org.jotaro.rag.retrieval.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jotaro.rag.retrieval.RetrievalHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrieverParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsElasticsearchHits() {
        List<RetrievalHit> hits = ElasticsearchRetriever.parse("""
                {"hits":{"hits":[{"_id":"1","_score":2.5,"_source":{"title":"登录","content":"从过滤器进入","source":"auth.md"}}]}}
                """, mapper);
        assertEquals(1, hits.size());
        assertEquals("elasticsearch", hits.get(0).route());
        assertEquals("登录", hits.get(0).title());
        assertEquals(2.5, hits.get(0).score());
    }

    @Test
    void readsTheFirstGraphArrayAndIgnoresEmptyData() {
        List<RetrievalHit> hits = GraphRetriever.parse("""
                {"data":{"search":[{"id":"g1","title":"图","content":"关系","score":1.2}]}}
                """, mapper);
        assertEquals("graph", hits.get(0).route());
        assertEquals("关系", hits.get(0).content());
        assertTrue(GraphRetriever.parse("{\"data\":{}}", mapper).isEmpty());
    }
}
