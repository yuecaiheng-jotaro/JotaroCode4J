package org.jotaro.jotarocode.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolQuestionnaireTest {

    @Test
    void onlyComplexToolsAsk() {
        assertTrue(ToolQuestionnaire.needsAsk("bash"));
        assertTrue(ToolQuestionnaire.needsAsk("powershell"));
        assertTrue(ToolQuestionnaire.needsAsk("write"));
        assertTrue(ToolQuestionnaire.needsAsk("edit"));
        assertFalse(ToolQuestionnaire.needsAsk("read"));
        assertFalse(ToolQuestionnaire.needsAsk("grep"));
    }

    @Test
    void parsesAllowDenyAndHint() {
        assertEquals(ToolQuestionnaire.Parsed.Kind.ALLOW_ONCE, ToolQuestionnaire.parse("y").kind());
        assertEquals(ToolQuestionnaire.Parsed.Kind.ALLOW_ALL, ToolQuestionnaire.parse("全部允许").kind());
        assertEquals(ToolQuestionnaire.Parsed.Kind.DENY, ToolQuestionnaire.parse("n").kind());
        assertEquals(ToolQuestionnaire.Parsed.Kind.AGAIN, ToolQuestionnaire.parse("  ").kind());
        assertEquals(ToolQuestionnaire.Parsed.Kind.HINT, ToolQuestionnaire.parse("先别删这个目录").kind());
        assertEquals("先别删这个目录", ToolQuestionnaire.parse("先别删这个目录").hint());
    }

    @Test
    void previewShowsTheCommandNotTheWholePayload() {
        List<String> lines = ToolQuestionnaire.prompt("bash", "{\"command\":\"rm -rf build\",\"timeout\":30}");
        assertEquals("╭ 允许执行 Bash", lines.get(0));
        assertEquals("│ rm -rf build", lines.get(1));
    }
}
