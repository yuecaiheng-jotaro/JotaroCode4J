package org.jotaro.jotarocode.tui;

import org.jotaro.jotarocode.agent.runtime.SubagentActivity;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentPaneTest {

    @Test
    void nestsToolsUnderTheAgentAndClosesTheBlock() {
        SubagentPane pane = new SubagentPane();
        List<String> opened = pane.accept(activity(SubagentActivity.Kind.STARTED, "找登录"));
        List<String> tool = pane.accept(activity(SubagentActivity.Kind.TOOL, "read\n{\"path\":\"src/Auth.java\"}"));
        assertEquals("explore · Read src/Auth.java", pane.status());

        List<String> done = pane.accept(activity(SubagentActivity.Kind.FINISHED, "登录从 AuthFilter 进入"));

        assertTrue(plain(opened.get(0)).contains("explore"));
        assertEquals("    Read src/Auth.java", plain(tool.get(0)));
        assertTrue(plain(String.join("\n", done)).contains("登录从 AuthFilter 进入"));
        assertTrue(plain(done.get(done.size() - 1)).contains("完成"));
        assertEquals(null, pane.status());
    }

    @Test
    void streamsTextInsteadOfRepeatingItAtTheEnd() {
        SubagentPane pane = new SubagentPane();
        pane.accept(activity(SubagentActivity.Kind.STARTED, "看一下"));
        List<String> text = pane.accept(activity(SubagentActivity.Kind.TEXT, "第一行\n第二行"));
        List<String> done = pane.accept(activity(SubagentActivity.Kind.FINISHED, "第一行\n第二行"));

        assertEquals(List.of("  第一行"), text.stream().map(SubagentPaneTest::plain).toList());
        assertEquals("  ⎿ 完成", plain(done.get(done.size() - 1)));
        assertTrue(done.stream().map(SubagentPaneTest::plain).noneMatch(line -> line.contains("第一行")));
    }

    private static SubagentActivity activity(SubagentActivity.Kind kind, String detail) {
        return new SubagentActivity("explore-1", "explore", "找登录", kind, detail);
    }

    private static String plain(String styled) {
        return AttributedString.fromAnsi(styled).toString();
    }
}
