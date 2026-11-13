package org.jotaro.rag.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RrfTest {

    @Test
    void sumsReciprocalRanksAcrossLists() {
        RetrievalHit a = hit("a");
        RetrievalHit b = hit("b");
        RetrievalHit c = hit("c");
        List<RetrievalHit> fused = Rrf.fuse(List.of(List.of(a, b), List.of(b, c)), 60, 5);
        assertEquals(List.of("b", "a", "c"), fused.stream().map(RetrievalHit::id).toList());
        assertEquals(1.0 / 61 + 1.0 / 62, fused.get(0).score(), 1e-12);
        assertEquals(1.0 / 61, fused.get(1).score(), 1e-12);
        assertEquals(1.0 / 62, fused.get(2).score(), 1e-12);
    }

    private static RetrievalHit hit(String id) {
        return new RetrievalHit(id, id, id, "", "postgres", 1);
    }
}
