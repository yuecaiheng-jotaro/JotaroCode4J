package org.jotaro.jotarocode.agent.rag;

import org.jotaro.jotarocode.config.JotaroAgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagBasesTest {

    @Test
    void emptyConfigDisablesTheKnowledgeBase() {
        RagBases.Choice choice = RagBases.select(List.of(), "handbook");
        assertFalse(choice.ready());
        assertTrue(choice.message().contains("未启用"));
        assertFalse(RagBases.enabled(List.of()));
        assertEquals("", RagBases.prompt(List.of()));
    }

    @Test
    void allConfigSearchesEveryBase() {
        RagBases.Choice choice = RagBases.select(List.of(base("ALL", "全部知识库")), "");
        assertTrue(choice.ready());
        assertEquals("", choice.knowledgeBase());
        assertEquals("", RagBases.select(List.of(base("ALL", "")), "ALL").knowledgeBase());
        assertEquals("handbook", RagBases.select(List.of(base("ALL", "")), "handbook").knowledgeBase());
        assertTrue(RagBases.prompt(List.of(base("ALL", ""))).contains("全部知识库"));
    }

    @Test
    void oneConfiguredBaseIsTheDefault() {
        RagBases.Choice choice = RagBases.select(List.of(base("handbook", "手册")), "");
        assertTrue(choice.ready());
        assertEquals("handbook", choice.knowledgeBase());
        assertTrue(RagBases.prompt(List.of(base("handbook", "手册"))).contains("handbook：手册"));
    }

    @Test
    void severalBasesRequireAKnownName() {
        List<JotaroAgentProperties.KnowledgeBase> bases = List.of(base("handbook", "手册"), base("notes", ""));
        RagBases.Choice missing = RagBases.select(bases, "");
        assertFalse(missing.ready());
        assertTrue(missing.message().contains("handbook、notes"));
        RagBases.Choice unknown = RagBases.select(bases, "other");
        assertFalse(unknown.ready());
        assertTrue(unknown.message().contains("other"));
        assertEquals("notes", RagBases.select(bases, "notes").knowledgeBase());
        assertTrue(RagBases.prompt(bases).contains("必须选其中一个"));
    }

    private static JotaroAgentProperties.KnowledgeBase base(String name, String description) {
        JotaroAgentProperties.KnowledgeBase base = new JotaroAgentProperties.KnowledgeBase();
        base.setName(name);
        base.setDescription(description);
        return base;
    }
}
