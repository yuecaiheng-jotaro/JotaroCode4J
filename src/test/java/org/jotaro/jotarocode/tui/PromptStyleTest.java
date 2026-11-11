package org.jotaro.jotarocode.tui;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 提示符的可见文字和列宽要一致，颜色不能把光标顶偏。 */
class PromptStyleTest {

    @Test
    void idlePromptUsesJotaroMark() {
        assertEquals("◆ jotaro ❯ ", plain(PromptStyle.left(false)));
        assertSameWidth(PromptStyle.left(false));
    }

    @Test
    void runningPromptUsesSteerMark() {
        assertEquals("◆ steer ❯ ", plain(PromptStyle.left(true)));
        assertSameWidth(PromptStyle.left(true));
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
