package org.jotaro.jotarocode.tui;

import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 斜杠命令和模型名都按当前词的前缀过滤，普通文字不出现在候选里。 */
class SlashCommandCompleterTest {

    private final SlashCommandCompleter completer = new SlashCommandCompleter(
            () -> List.of("glm-4.6", "deepseek-chat")
    );

    @Test
    void completesCommandsByPrefix() {
        assertEquals(List.of("/help", "/exit", "/quit", "/abort", "/reset", "/model"), values("/"));
        assertEquals(List.of("/model"), values("/mo"));
        assertEquals(List.of("/exit"), values("/e"));
        assertEquals(List.of(), values("读一下"));
    }

    @Test
    void completesModelNamesAfterModelCommand() {
        assertEquals(List.of("deepseek-chat", "glm-4.6"), values("/model "));
        assertEquals(List.of("deepseek-chat"), values("/model de"));
        assertEquals(List.of(), values("/model no-such"));
    }

    private List<String> values(String line) {
        ParsedLine parsed = new DefaultParser().parse(line, line.length());
        List<Candidate> candidates = new ArrayList<>();
        completer.complete(null, parsed, candidates);
        return candidates.stream().map(Candidate::value).toList();
    }
}
