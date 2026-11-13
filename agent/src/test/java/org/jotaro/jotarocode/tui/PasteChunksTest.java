package org.jotaro.jotarocode.tui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasteChunksTest {

    @Test
    void shortTextStaysInlineAndLongTextCollapses() {
        PasteChunks chunks = new PasteChunks();
        assertEquals("你好", chunks.text("你好"));
        assertEquals("1\n2\n3\n4\n5\n", chunks.text("1\n2\n3\n4\n5\n"));

        String pasted = "1\n2\n3\n4\n5\n6";
        String label = chunks.text(pasted);
        assertEquals("[Pasted text #1 +6 lines]", label);
        assertEquals("看 " + pasted, chunks.expand("看 " + label).text());
    }

    @Test
    void longSingleLineCollapsesToOneLineLabel() {
        PasteChunks chunks = new PasteChunks();
        String label = chunks.text("a".repeat(801));
        assertEquals("[Pasted text #1 +1 lines]", label);
        assertEquals("a".repeat(801), chunks.expand(label).text());
    }

    @Test
    void imageBecomesOneTokenAndBackspaceCoversTheWholeLabel() throws Exception {
        Path png = Files.createTempFile("shot", ".png");
        Files.write(png, new byte[]{1, 2, 3});
        try {
            PasteChunks chunks = new PasteChunks();
            assertTrue(PasteChunks.isImageFile(png.toString()));
            String label = chunks.image(png);
            assertEquals("[Image #1]", label);
            String line = "看 " + label;
            PasteChunks.Expanded expanded = chunks.expand(line);
            assertEquals("看 ", expanded.text());
            assertEquals(png, expanded.images().get(0));
            assertEquals("请看附图。", chunks.expand(label).text());

            PasteChunks.Span span = chunks.covering(line, line.indexOf('#'));
            assertEquals(line.indexOf('['), span.start());
            assertEquals(label, span.label());
            assertNull(chunks.covering(line, 0));
        } finally {
            Files.deleteIfExists(png);
        }
    }

    @Test
    void imageClipboardWinsOnlyWhenItIsListedFirst() {
        assertTrue(ClipboardImages.imageIsPrimary("«class PNGf», 20"));
        assertTrue(ClipboardImages.imageIsPrimary("«class PNGf», 20, «class utf8», 4"));
        assertFalse(ClipboardImages.imageIsPrimary("«class utf8», 4, «class PNGf», 20"));
        assertFalse(ClipboardImages.imageIsPrimary("string, 4"));
    }
}
