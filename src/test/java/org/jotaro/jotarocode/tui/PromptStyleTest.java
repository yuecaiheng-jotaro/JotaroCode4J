package org.jotaro.jotarocode.tui;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 提示符的可见文字和列宽要一致，颜色不能把光标顶偏。 */
class PromptStyleTest {

    @Test
    void inputMarkStaysNarrow() {
        assertEquals("❯ ", plain(PromptStyle.left(false)));
        assertSameWidth(PromptStyle.left(false));
        assertEquals(plain(PromptStyle.left(false)), plain(PromptStyle.left(true)));
    }

    @Test
    void runningFrameKeepsTheLiveLineAboveTheBox() {
        String shown = plain(PromptStyle.frame(true, "你好", false, "正在回复", 24));

        assertTrue(shown.startsWith("你好\n╭─ steer"));
        assertTrue(shown.endsWith("❯ "));
    }

    @Test
    void runningFrameUsesStatusUntilTextArrives() {
        String shown = plain(PromptStyle.frame(true, "", false, "正在回复", 24));

        assertTrue(shown.startsWith("正在回复\n╭─ steer"));
        assertTrue(shown.endsWith("❯ "));
    }

    @Test
    void idleFrameIsOnlyTheBox() {
        String shown = plain(PromptStyle.frame(false, "残留", false, "正在回复", 24));

        assertTrue(shown.startsWith("╭─ jotaro"));
        assertTrue(shown.endsWith("❯ "));
        assertTrue(!shown.contains("残留"));
    }

    @Test
    void tailKeepsTheNewestColumns() {
        assertEquals("abcdef", PromptStyle.tail("abcdef", 8));
        assertEquals("…cdef", PromptStyle.tail("abcdef", 6));
    }

    @Test
    void modelSitsOnTheRight() {
        assertEquals("deepseek-chat", plain(PromptStyle.right("deepseek-chat")));
        assertNull(PromptStyle.right(" "));
        assertNull(PromptStyle.right(null));
    }

    private static void assertSameWidth(String styled) {
        AttributedString rendered = AttributedString.fromAnsi(styled);
        String visible = rendered.toString();
        assertEquals(visible.codePointCount(0, visible.length()), rendered.columnLength());
    }

    private static String plain(String styled) {
        return AttributedString.fromAnsi(styled).toString();
    }
}
