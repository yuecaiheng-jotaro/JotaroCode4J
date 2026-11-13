package org.jotaro.rag.index;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkerTest {

    @Test
    void packsShortParagraphsAndOverlapsLongOnes() {
        assertEquals(List.of("甲\n\n乙"), new Chunker().split("甲\n\n乙"));
        String paragraph = "a".repeat(1500);
        List<String> parts = new Chunker(700, 80).split(paragraph);
        assertEquals(3, parts.size());
        assertEquals(paragraph.substring(0, 700), parts.get(0));
        assertTrue(parts.get(1).startsWith(paragraph.substring(620, 700)));
    }
}
